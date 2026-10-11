#!/usr/bin/env python3
"""Static *type* checking for calls inside this project, without a Kotlin compiler.

`check_symbols.py` proves that a member exists; `check_calls.py` proves the call has the right
number of arguments. Neither notices the mistakes that actually broke the Android build:

    val contact = graph.onDb { store.contact(peerHash) } ?: return
    sendEphemeral(contact, payload)        # sendEphemeral(peerHash: String, …) — Contact passed

    update { it.copy(queued = graph.onDb { store.cryptoCount() }) }   # suspend call in a plain lambda

Both are *type* errors, and the app is large enough that a compile is the only other way to find
them. This checker finds them from the source alone, with two narrow rules that are deliberately
conservative — a missed finding is a compile away, a wrong one wastes a whole build cycle:

1. **An object where a builtin belongs (or the reverse).** The type of every `val`, parameter and
   property is tracked per scope; a call that passes such a value where the declared parameter is a
   Kotlin builtin (`String`, `Long`, …) — or the reverse — is reported. A builtin is never a
   project type, so a disagreement is always a real error; two project types are never compared,
   because they may be related through an interface.

2. **A suspend call inside a non-suspend lambda.** A lambda literal may only call a suspend
   function when it is passed to an `inline` function, or to a parameter declared with a `suspend`
   function type. Anything else is the classic
   “Suspension functions can be called only within coroutine body” error.

Requiring a *unique* declaration and a *known* receiver for every call site is what keeps the
false-positive rate at zero: calls the checker cannot resolve are simply not judged. `--self-test`
runs both rules over a snippet that contains the bugs (and a few look-alikes that must stay quiet),
so the checker cannot silently stop working.

    python3 tools/static-checks/check_types.py            # report
    python3 tools/static-checks/check_types.py --strict   # non-zero exit on findings or self-test failure
"""

from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from check_symbols import mask_literals, read_sources  # noqa: E402

# `Any` and `Nothing` are deliberately absent: a value typed `Any` carries no information, so it
# must read as "unknown" here rather than as a builtin that everything is compared against.
BUILTINS = {
    "String", "Int", "Long", "Short", "Byte", "Boolean", "Char", "Double", "Float",
    "ByteArray", "Unit", "UInt", "ULong", "UByte", "UShort", "Duration",
}

# Standard-library functions whose lambdas are inline, so a suspend call inside them is legal
# whenever the surrounding function is suspend.
INLINE_STDLIB = {
    "let", "run", "apply", "also", "with", "use", "repeat", "map", "mapNotNull", "flatMap",
    "forEach", "forEachIndexed", "filter", "filterNot", "filterNotNull", "firstOrNull",
    "lastOrNull", "any", "all", "none", "count", "sumOf", "sortedBy", "sortedWith", "associate",
    "associateBy", "groupBy", "partition", "takeIf", "takeUnless", "onEach", "onSuccess",
    "onFailure", "getOrElse", "getOrNull", "mapValues", "mapKeys", "requireNotNull",
    "checkNotNull", "buildList", "buildMap", "buildSet", "buildString", "generateSequence",
    "measureTimeMillis", "synchronized", "runCatching", "minByOrNull", "maxByOrNull",
    "singleOrNull", "indexOfFirst", "fold", "reduce", "zip", "chunked", "windowed", "lazy",
}

# Kotlin keywords that may be followed by `(` or `{` without being a call.
KEYWORD_CALLS = {
    "if", "for", "while", "when", "catch", "fun", "return", "is", "in", "as", "else", "try",
    "do", "object", "class", "interface", "val", "var", "init", "constructor", "super", "this",
    "break", "continue", "throw", "package", "import", "typealias", "enum", "sealed", "data",
    "suspend", "inline", "private", "public", "internal", "protected", "override", "open",
    "abstract", "annotation", "companion", "operator", "infix", "lateinit", "const", "tailrec",
}

# `name(` — used when the argument list is needed.
PAREN_CALL_RE = re.compile(r"(?<![\w])([a-zA-Z]\w*)[ \t]*\(")
# `name(` or `name {` — a call in either form. Member calls (`store.contact(…)`) are included on
# purpose: those are exactly the calls whose arguments are being misused.
ANY_CALL_RE = re.compile(r"(?<![\w])([a-zA-Z]\w*)[ \t]*(?=\(|\{)")
CLASS_DECL_RE = re.compile(r"\b(?:class|object|interface|enum)\s+(\w+)")
FUN_DECL_RE = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*\n[ \t]*)*"
    r"(?P<mods>(?:(?:public|private|internal|protected|open|override|inline|suspend|operator|"
    r"infix|tailrec|external|abstract|actual|expect)\s+)*)"
    r"fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,\s]+?\.)?(?P<name>\w+)\s*\(",
    re.M,
)

WRAPPERS = {
    "List", "MutableList", "Set", "MutableSet", "Collection", "Sequence", "Array", "Map",
    "MutableMap", "StateFlow", "MutableStateFlow", "Flow", "SharedFlow", "Iterator",
}
CLASS_NAMES: set[str] = set()


# --------------------------------------------------------------------------- parsing helpers


@dataclass
class Param:
    name: str
    type_text: str
    has_default: bool


@dataclass
class Fun:
    name: str
    params: list[Param]
    return_type: str | None
    suspend: bool
    inline: bool
    owner: str | None
    file: str
    line: int

    def describe(self) -> str:
        shape = ", ".join(p.name + ": " + p.type_text for p in self.params)
        return f"{self.name}({shape}) -> {self.return_type or '?'}"


@dataclass
class Scope:
    start: int
    end: int
    types: dict[str, str | None] = field(default_factory=dict)

    def contains(self, position: int) -> bool:
        return self.start <= position < self.end

    @property
    def span(self) -> int:
        return self.end - self.start


def split_top_level_pos(text: str, base: int) -> list[tuple[str, int]]:
    """Splits on top-level commas, also returning the offset of each chunk."""
    parts: list[tuple[str, int]] = []
    depth, current, start, quote = 0, [], 0, None
    for index, char in enumerate(text):
        if quote:
            if char == quote and index and text[index - 1] != "\\":
                quote = None
            continue
        if char in "\"'":
            quote = char
            continue
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        if char == "," and depth == 0:
            chunk = "".join(current).strip()
            if chunk:
                offset = start + len("".join(current)) - len("".join(current).lstrip())
                parts.append((chunk, base + offset))
            current, start = [], index + 1
            continue
        if not current and char.isspace():
            start = index + 1
            continue
        current.append(char)
    chunk = "".join(current).strip()
    if chunk:
        offset = start + len("".join(current)) - len("".join(current).lstrip())
        parts.append((chunk, base + offset))
    return parts


def balanced(text: str, start: int) -> tuple[str, int]:
    """Content of the bracket group starting at the bracket at `start`, and the index after it."""
    depth, index, quote = 0, start, None
    while index < len(text):
        char = text[index]
        if quote:
            if char == quote and text[index - 1] != "\\":
                quote = None
        elif char in "\"'":
            quote = char
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index], index + 1
        index += 1
    return "", len(text)


def parse_params(text: str) -> list[Param]:
    params: list[Param] = []
    for chunk, _ in split_top_level_pos(text, 0):
        chunk = re.sub(r"^(@\w+(?:\([^)]*\))?\s*)+", "", chunk).removeprefix("vararg ").strip()
        match = re.match(r"(?:val |var |crossinline |noinline )*(\w+)\s*:\s*(.*)$", chunk, re.S)
        if not match:
            continue
        name, rest = match.group(1), match.group(2)
        depth, cut = 0, len(rest)
        for index, char in enumerate(rest):
            if char in "<([{":
                depth += 1
            elif char in ">)]}":
                depth -= 1
            elif char == "=" and depth == 0:
                cut = index
                break
        params.append(Param(name, rest[:cut].strip(), cut < len(rest)))
    return params


def return_type_after(masked: str, index: int) -> str | None:
    match = re.match(r"\s*:\s*", masked[index:])
    if not match:
        return None
    cursor, depth = index + match.end(), 0
    while cursor < len(masked):
        char = masked[cursor]
        if char in "<([":
            depth += 1
        elif char in ">)]":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and char in "={":
            break
        elif depth == 0 and char == "\n":
            break
        cursor += 1
    text = masked[index + match.end():cursor].strip()
    return text if text and not text.startswith("where") else None


def simple_type(text: str | None) -> str | None:
    """`Contact?` -> `Contact`; `List<Contact>` -> `Contact`; `List<Int>` -> `Int`."""
    if not text:
        return None
    text = text.strip()
    if text.startswith("suspend "):
        return "suspend"
    base = re.split(r"[<(\[]", text)[0].strip().rstrip("?").strip()
    inner = re.match(r"^[A-Za-z_]\w*<(.+)>$", text.rstrip("?").strip(), re.S)
    if base in WRAPPERS and inner:
        element = simple_type(inner.group(1))
        if element in BUILTINS or element in CLASS_NAMES:
            return element
    return base or None


def is_declaration(masked: str, start: int) -> bool:
    """True when `name` at `start` is being declared (`fun name(…)`, `class Name(…)`, …)."""
    before = masked[:start]
    if re.search(r"\bfun\s+[<><>\w.,\s]*$", before):
        return True
    return bool(re.search(r"\b(?:class|interface|object|enum|constructor)\s+\w*\s*$", before))


def class_ranges(masked: str) -> list[tuple[int, int, str]]:
    """(body start, body end, name) of every class/object/interface in one file.

    Tracking the braces matters: a `private class` nested inside another class would otherwise
    become the "owner" of every declaration that follows it, and a call above it would look like it
    belonged to a different type than the function it calls.
    """
    ranges: list[tuple[int, int, str]] = []
    for match in CLASS_DECL_RE.finditer(masked):
        cursor, limit = match.end(), match.end() + 600
        while cursor < len(masked) and cursor < limit and masked[cursor] not in "{":
            if masked[cursor] == "\n":
                following = masked[cursor + 1:]
                next_line = following.split("\n", 1)[0]
                if next_line[:1] not in ("", " ", "\t"):
                    break                       # declaration ended without a body
            cursor += 1
        if cursor >= len(masked) or cursor >= limit or masked[cursor] != "{":
            continue
        _body, closing = balanced(masked, cursor)
        ranges.append((cursor, closing, match.group(1)))
    return ranges


def owner_in(ranges: list[tuple[int, int, str]], position: int) -> str | None:
    """Innermost class/object whose body contains `position`."""
    inside = [r for r in ranges if r[0] <= position < r[1]]
    if not inside:
        return None
    return min(inside, key=lambda r: r[1] - r[0])[2]


def owner_at(masked: str, position: int) -> str | None:
    """Enclosing type without precomputed ranges — convenient, but see `owner_in`."""
    return owner_in(class_ranges(masked), position)


# --------------------------------------------------------------------------- declarations


def declarations(sources: dict[str, str]) -> dict[str, list[Fun]]:
    found: dict[str, list[Fun]] = {}
    names: set[str] = set()
    for path, source in sources.items():
        masked = mask_literals(source)
        names.update(CLASS_DECL_RE.findall(masked))
        ranges = class_ranges(masked)
        for match in FUN_DECL_RE.finditer(masked):
            params_text, after = balanced(source, match.end() - 1)
            found.setdefault(match.group("name"), []).append(
                Fun(
                    name=match.group("name"),
                    params=parse_params(params_text),
                    return_type=return_type_after(masked, after),
                    suspend="suspend" in match.group("mods"),
                    inline="inline" in match.group("mods"),
                    owner=owner_in(ranges, match.start()),
                    file=os.path.basename(path),
                    line=masked[:match.start()].count("\n") + 1,
                )
            )
    CLASS_NAMES.clear()
    CLASS_NAMES.update(names)
    return found


# A property declaration with an explicit type: `val store: PingStore by lazy { … }`,
# `private val store get() = graph.store`, `lateinit var handle: RandomAccessFile`, `val x: Int = 1`.
EXPLICIT_PROP_RE = re.compile(
    r"(?<![\w.])(?:val|var)\s+(\w+)\s*:\s*([\w.<>?,]+)\s*(?:=|,|\)|by\b|get\b|where\b|$)",
    re.M,
)


def property_types(sources: dict[str, str]) -> dict[str, dict[str, str]]:
    """{Class: {property: type}} for explicit property declarations, used to follow `a.b` chains."""
    properties: dict[str, dict[str, str]] = {}
    for path, source in sources.items():
        masked = mask_literals(source)
        ranges = class_ranges(masked)
        for match in EXPLICIT_PROP_RE.finditer(masked):
            owner = owner_in(ranges, match.start())
            found = simple_type(match.group(2))
            if not owner or not found or (found not in BUILTINS and found not in CLASS_NAMES):
                continue
            properties.setdefault(owner, {})[match.group(1)] = found
    return properties


def chain_type(expression: str, position: int, scopes: list[Scope],
               properties: dict[str, dict[str, str]]) -> str | None:
    """Type of a plain property chain such as `graph.store`, or None when it cannot be followed."""
    text = expression.strip().rstrip("?").strip()
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\s*[?.]\s*[A-Za-z_]\w*)*", text):
        return None
    parts = [part.strip() for part in re.split(r"\s*[?.]\s*", text) if part.strip()]
    current = scope_type_in(parts[0], position, scopes)
    for part in parts[1:]:
        if not current:
            return None
        current = properties.get(current, {}).get(part)
    return current


def unique_shape(shapes: list[Fun] | None, owner: str | None, receiver: str | None) -> Fun | None:
    """The single declaration a call can mean, or None when it cannot be decided."""
    if not shapes:
        return None
    if receiver is not None:
        shapes = [s for s in shapes if s.owner == receiver]
    elif owner is not None:
        shapes = [s for s in shapes if s.owner == owner or s.owner is None]
    if len(shapes) != 1:
        return None
    return shapes[0]


# --------------------------------------------------------------------------- scopes


@dataclass
class FunctionBody:
    """A function declaration with the character range of its body."""

    fun: Fun
    owner: str | None
    body_start: int
    body_end: int


def function_bodies(masked: str, source: str, ranges: list[tuple[int, int, str]]) -> list[FunctionBody]:
    bodies: list[FunctionBody] = []
    for match in FUN_DECL_RE.finditer(masked):
        params_text, after = balanced(source, match.end() - 1)
        cursor = after
        # Skip an expression body / trailing declaration text, then take the block body if present.
        while cursor < len(masked) and masked[cursor] not in "{\n=":
            cursor += 1
        if cursor >= len(masked):
            continue
        owner = owner_in(ranges, match.start())
        if masked[cursor] == "=":
            end_of_line = masked.find("\n", cursor)
            bodies.append(
                FunctionBody(
                    Fun(
                        name=match.group("name"),
                        params=parse_params(params_text),
                        return_type=return_type_after(masked, after),
                        suspend="suspend" in match.group("mods"),
                        inline="inline" in match.group("mods"),
                        owner=owner,
                        file="",
                        line=0,
                    ),
                    owner,
                    cursor,
                    len(masked) if end_of_line == -1 else end_of_line,
                )
            )
            continue
        _body, closing = balanced(masked, cursor)
        bodies.append(
            FunctionBody(
                Fun(
                    name=match.group("name"),
                    params=parse_params(params_text),
                    return_type=return_type_after(masked, after),
                    suspend="suspend" in match.group("mods"),
                    inline="inline" in match.group("mods"),
                    owner=owner,
                    file="",
                    line=0,
                ),
                owner,
                cursor,
                closing,
            )
        )
    return bodies


def call_return_type(name: str, decls: dict[str, list[Fun]], owner: str | None,
                     receiver_type: str | None) -> str | None:
    """The type a call produces, when every candidate declaration agrees on one builtin/class."""
    shapes = decls.get(name)
    fun = unique_shape(shapes, owner, receiver_type)
    if fun is None:
        return None
    found = simple_type(fun.return_type)
    if found and (found in BUILTINS or found in CLASS_NAMES):
        return found
    return None


def paren_depth_at(text: str, index: int) -> int:
    """How deep inside `( … )` a position sits. Braces do not count: a lambda body is the value."""
    depth = 0
    for char in text[:index]:
        if char == "(":
            depth += 1
        elif char == ")" and depth:
            depth -= 1
    return depth


def value_type_from_expression(tail: str, position: int, decls: dict[str, list[Fun]],
                               scopes: list[Scope], properties: dict[str, dict[str, str]],
                               owner: str | None) -> str | None:
    """The type of the value an expression produces.

    The value is the result of the *outermost* call: `ChatMessage(expiryFor(…))` is a ChatMessage
    and not a Long, and in `store.contact(hash) ?: fallback()` the first call decides. A call whose
    receiver cannot be typed, or that this project does not declare at all (standard library,
    coroutines), makes the answer unknown — being wrong here would report a bug that is not there.
    """
    stripped = tail.strip()
    # A control-flow expression (`try { … } catch …`, `if …`) takes the type of its branches, and a
    # chain step on its own line hides its receiver: neither can be decided from here.
    if re.match(r"^(try|if|when|while|for|synchronized)\b", stripped):
        return None
    if any(receiver_name(tail, m.start()) == "" for m in ANY_CALL_RE.finditer(tail)):
        return None
    # `graph.onDb { store.group(id) }.id` produces whatever `.id` is, not a GroupRecord: with a
    # property access at the end the call's return type says nothing about the value.
    if re.search(r"\.[A-Za-z_]\w*\s*$", stripped):
        return None

    outermost: int | None = None
    for call in ANY_CALL_RE.finditer(tail):
        depth = paren_depth_at(tail, call.start())
        if outermost is None or depth < outermost:
            outermost = depth
        if depth > outermost:
            continue                        # an argument of the outermost call, not its value
        name = call.group(1)
        if name in KEYWORD_CALLS:
            continue
        if name in CLASS_NAMES:
            return name                     # `val x = SomeClass(…)` produces a SomeClass
        receiver = receiver_name(tail, call.start())
        receiver_type = None
        if receiver is not None:
            receiver_type = (
                receiver if receiver in CLASS_NAMES
                else scope_type_in(receiver, position, scopes)
            )
            if receiver_type is None:
                return None
        shapes = decls.get(name)
        if not shapes:
            return None
        fun = unique_shape(shapes, owner, receiver_type)
        if fun is None:
            return None
        found = simple_type(fun.return_type)
        if found and (found in BUILTINS or found in CLASS_NAMES):
            return found
        # A generic return type (`fun <T> onDb(block: () -> T): T`) says nothing: the call inside
        # the braces is the one that decides, so keep looking.
    return None


def lambda_parameters(masked: str, decls: dict[str, list[Fun]]) -> list[tuple[int, list[str]]]:
    """`{ handle -> … }` style parameters, with the range of the lambda that introduces them."""
    found: list[tuple[int, list[str]]] = []
    for match in ANY_CALL_RE.finditer(masked):
        name = match.group(1)
        if name in KEYWORD_CALLS or is_declaration(masked, match.start()):
            continue
        cursor = match.end()
        while cursor < len(masked) and masked[cursor] in " \t\r\n":
            cursor += 1
        candidates: list[tuple[int, int]] = []
        if cursor < len(masked) and masked[cursor] == "{":
            _body, closing = balanced(masked, cursor)
            candidates.append((cursor, closing))
        elif cursor < len(masked) and masked[cursor] == "(":
            args, after = balanced(masked, cursor)
            for chunk, offset in split_top_level_pos(args, cursor + 1):
                if chunk.startswith("{"):
                    candidates.append((offset, balanced(masked, offset)[1]))
            trailing = after
            while trailing < len(masked) and masked[trailing] in " \t\r\n":
                trailing += 1
            if trailing < len(masked) and masked[trailing] == "{":
                candidates.append((trailing, balanced(masked, trailing)[1]))
        for start, end in candidates:
            head = masked[start + 1: start + 120]
            arrow = re.match(r"\s*([\w\s,]*?)\s*->", head)
            if not arrow:
                continue
            names = [n.strip() for n in arrow.group(1).split(",") if n.strip()]
            if names and all(re.fullmatch(r"\w+", n) for n in names):
                found.append((start, names))
    return found


def build_scopes(path: str, source: str, masked: str, decls: dict[str, list[Fun]],
                 class_members: dict[str, set[str]], ranges: list[tuple[int, int, str]],
                 properties: dict[str, dict[str, str]]) -> list[Scope]:
    """One scope per function body plus a file scope, each holding its parameters and `val`s."""
    bodies = function_bodies(masked, source, ranges)
    parameter_spans = [
        (match.end() - 1, balanced(source, match.end() - 1)[1])
        for match in FUN_DECL_RE.finditer(masked)
    ]
    scopes: list[Scope] = [Scope(0, len(masked))]

    def scope_for(position: int) -> Scope:
        inside = [b for b in bodies if b.body_start <= position < b.body_end]
        if not inside:
            return scopes[0]
        smallest = min(inside, key=lambda b: b.body_end - b.body_start)
        for scope in scopes[1:]:
            if scope.start == smallest.body_start and scope.end == smallest.body_end:
                return scope
        scope = Scope(smallest.body_start, smallest.body_end)
        scopes.append(scope)
        return scope

    # Parameters first: they shadow everything and are always known.
    for body in bodies:
        scope = scope_for(body.body_start)
        for param in body.fun.params:
            found = simple_type(param.type_text)
            scope.types[param.name] = found if found and (
                found in BUILTINS or found in CLASS_NAMES
            ) else None

    # Lambda parameters (`{ handle -> … }`) shadow outer names with an unknown type.
    for start, names in lambda_parameters(masked, decls):
        scope = scope_for(start)
        for name in names:
            scope.types.setdefault(name, None)

    # Explicitly typed `val`s, properties and constructor properties. An initializer is optional
    # (`private lateinit var store: Store`), and a constructor property ends at `,` or `)` rather
    # than at a newline. Parameters of a *function* are excluded: they belong to that function.
    for match in EXPLICIT_PROP_RE.finditer(masked):
        if any(start <= match.start() < end for start, end in parameter_spans):
            continue
        found = simple_type(match.group(2))
        if not found or (found not in BUILTINS and found not in CLASS_NAMES):
            continue
        scope = scope_for(match.start())
        existing = scope.types.get(match.group(1), "missing")
        if existing != "missing" and existing != found:
            scope.types[match.group(1)] = None       # ambiguous: two different types, one name
        else:
            scope.types[match.group(1)] = found

    # Properties that are a plain chain: `val store get() = graph.store`, `val db = graph.database`.
    # Two passes so that a chain referring to another chain also resolves.
    for _ in range(2):
        for match in re.finditer(
            r"(?<![\w.])(?:val|var)\s+(\w+)\s*(?:get\(\)\s*=|=\s*)([^\n=]*)", masked
        ):
            name, expression = match.group(1), match.group(2)
            scope = scope_for(match.start())
            if scope.types.get(name, "missing") != "missing":
                continue
            found = chain_type(expression, match.start(), scopes, properties)
            if found:
                scope.types[name] = found

    # Inferred `val`s: the type produced by the last resolvable call in the expression.
    for match in re.finditer(r"(?<![\w.])(?:val|var)\s+(\w+)\s*=", masked):
        scope = scope_for(match.start())
        if scope.types.get(match.group(1), "missing") != "missing":
            continue
        indent = match.start() - (masked.rfind("\n", 0, match.start()) + 1)
        indent -= len(masked[masked.rfind("\n", 0, match.start()) + 1:match.start()].lstrip())
        tail = statement_tail(masked, match.end(), indent)
        owner = owner_in(ranges, match.start())
        if tail.strip().startswith("{"):
            # `val f = { … }`: a lambda, not a call.
            continue
        inferred = value_type_from_expression(tail, match.start(), decls, scopes, properties, owner)
        if inferred:
            scope.types[match.group(1)] = inferred
    return scopes


def statement_tail(masked: str, start: int, indent: int, limit: int = 600) -> str:
    """Text of the expression that begins at `start`, including continuation lines."""
    end = start
    while end < len(masked):
        line_end = masked.find("\n", end)
        if line_end == -1:
            return masked[start:]
        following = masked[line_end + 1:]
        next_line = following.split("\n", 1)[0]
        stripped = next_line.lstrip()
        leading = len(next_line) - len(stripped)
        if stripped and leading <= indent and stripped[0] not in ")}]?:.,+*/&|=":
            return masked[start:line_end]
        end = line_end + 1
        if end - start > limit:
            break
    return masked[start:end]


def receiver_name(text: str, call_start: int) -> str | None:
    """The receiver a call is made on.

    `handle.write(` -> `handle`; `Bytes.fromBase64Url(` -> `Bytes`; `write(` -> None (unqualified);
    `""` (a sentinel) for a chain continuation on its own line (`.toByteArray()`), whose receiver
    cannot be seen from the call itself.
    """
    before = text[:call_start]
    match = re.search(r"([A-Za-z_]\w*)\s*[?]?\.\s*$", before)
    if match:
        name = match.group(1)
        return None if name in ("this", "super", "it") else name
    if re.search(r"[.?]\s*$", before):
        return ""
    return None


def scope_type_in(name: str, position: int, scopes: list[Scope]) -> str | None:
    """Type of an identifier at `position`, innermost scope first. None when unknown/shadowed."""
    for scope in sorted([s for s in scopes if s.contains(position)], key=lambda s: s.span):
        if name in scope.types:
            return scope.types[name]
    return None


# --------------------------------------------------------------------------- rule 2 regions


@dataclass
class Region:
    """A brace group that is a lambda literal passed to a call."""

    start: int
    end: int
    accepting: bool
    owner: str

    @property
    def span(self) -> int:
        return self.end - self.start


def lambda_regions(masked: str, decls: dict[str, list[Fun]]) -> list[Region]:
    regions: list[Region] = []
    for match in ANY_CALL_RE.finditer(masked):
        name = match.group(1)
        if name in KEYWORD_CALLS or is_declaration(masked, match.start()):
            continue
        shapes = decls.get(name)
        if shapes:
            # A lambda may call suspend functions when the function is inline, or when the
            # parameter is declared with a `suspend` function type. Declarations that disagree are
            # treated as permissive: missing a bug beats a false alarm.
            accepting = any(
                shape.inline or any(p.type_text.startswith("suspend ") for p in shape.params)
                for shape in shapes
            )
        else:
            # Standard library inline functions and everything this project does not declare
            # (coroutines, Android callbacks) — unknown, therefore not judged.
            accepting = True
        cursor = match.end()
        while cursor < len(masked) and masked[cursor] in " \t\r\n":
            cursor += 1
        if cursor >= len(masked):
            continue
        if masked[cursor] == "{":
            _body, closing = balanced(masked, cursor)
            regions.append(Region(cursor, closing, accepting, name))
        elif masked[cursor] == "(":
            args, after = balanced(masked, cursor)
            for chunk, offset in split_top_level_pos(args, cursor + 1):
                if chunk.startswith("{"):
                    _body, closing = balanced(masked, offset)
                    regions.append(Region(offset, closing, accepting, name))
            trailing = after
            while trailing < len(masked) and masked[trailing] in " \t\r\n":
                trailing += 1
            if trailing < len(masked) and masked[trailing] == "{":
                _body, closing = balanced(masked, trailing)
                regions.append(Region(trailing, closing, accepting, name))
    return regions


# --------------------------------------------------------------------------- the checks


def check_file(path: str, source: str, decls: dict[str, list[Fun]],
               class_members: dict[str, set[str]],
               properties: dict[str, dict[str, str]] | None = None) -> list[str]:
    findings: list[str] = []
    masked = mask_literals(source)
    properties = properties if properties is not None else {}
    ranges = class_ranges(masked)
    scopes = build_scopes(path, source, masked, decls, class_members, ranges, properties)
    regions = lambda_regions(masked, decls)

    # ---------------------------------------------------------- rule 1: object vs builtin
    for match in PAREN_CALL_RE.finditer(masked):
        name = match.group(1)
        if name in KEYWORD_CALLS or is_declaration(masked, match.start()):
            continue
        owner = owner_in(ranges, match.start())
        receiver = receiver_name(masked, match.start())
        receiver_type = None
        if receiver == "":
            continue                            # chain continuation: cannot be resolved
        if receiver is not None:
            receiver_type = (
                receiver if receiver in CLASS_NAMES
                else scope_type_in(receiver, match.start(), scopes)
            )
            if receiver_type is None:
                continue                        # a member call on something we cannot type
            if name not in class_members.get(receiver_type, set()):
                continue                        # declares no such member: a different class
        fun = unique_shape(decls.get(name), owner, receiver_type)
        if fun is None:
            continue

        args, _after = balanced(source, match.end() - 1)
        for index, (chunk, _offset) in enumerate(split_top_level_pos(args, match.end())):
            if index >= len(fun.params):
                break
            if re.match(r"^\s*\w+\s*=[^=]", chunk) or chunk.startswith("{"):
                continue
            argument = re.match(r"^([A-Za-z_]\w*)(?:!!)?$", chunk.strip())
            if not argument:
                continue
            local = scope_type_in(argument.group(1), match.start(), scopes)
            expected = simple_type(fun.params[index].type_text)
            if not local or not expected or local == expected:
                continue
            # Only builtin-versus-object disagreements are reported: two project types may be
            # related through an interface, and two builtins may be `Int`/`Long` overloads.
            if (local in BUILTINS) == (expected in BUILTINS):
                continue
            if local not in BUILTINS and local not in CLASS_NAMES:
                continue
            if expected not in BUILTINS and expected not in CLASS_NAMES:
                continue
            line = masked[:match.start()].count("\n") + 1
            findings.append(
                f"{os.path.basename(path)}:{line}: {name}(…) — argument {index + 1} "
                f"`{argument.group(1)}` is {local}, the parameter {fun.params[index].name} takes "
                f"{fun.params[index].type_text}  [{fun.file}:{fun.line} declares {fun.describe()}]"
            )

    # ---------------------------------------------------------- rule 2: suspend in a plain lambda
    suspend_names = {n for n, shapes in decls.items() if all(s.suspend for s in shapes)}
    for match in ANY_CALL_RE.finditer(masked):
        name = match.group(1)
        if name not in suspend_names or is_declaration(masked, match.start()):
            continue
        position = match.start()
        enclosing = [r for r in regions if r.start < position < r.end]
        if not enclosing:
            continue                            # directly in a suspend function body: fine
        innermost = min(enclosing, key=lambda r: r.span)
        if innermost.accepting:
            continue
        line = masked[:position].count("\n") + 1
        findings.append(
            f"{os.path.basename(path)}:{line}: suspend call {name}(…) sits inside a "
            f"non-suspend lambda passed to {innermost.owner}(…) — "
            f"“Suspension functions can be called only within coroutine body”"
        )
    return findings


# --------------------------------------------------------------------------- self-test

SELF_TEST = """
class JsonValue
class Contact(val hash: String)
class ChatMessage(val id: String)
class Json {
    companion object {
        fun write(value: JsonValue): String = ""
        fun arr(values: List<JsonValue>): String = ""
    }
}
object Store {
    fun contact(hash: String): Contact? = null
    fun message(id: String): ChatMessage? = null
}
class Engine {
    private lateinit var store: Store
    private lateinit var cryptoStore: Store
    private fun update(transform: (Int) -> Int) {}
    suspend fun onDb(block: () -> Any?): Any? = null
    suspend fun sendEphemeral(peerHash: String) {}
    suspend fun direct() {
        // Must not be flagged: the body brace of a function is not a lambda.
        val message = onDb { store.message("z") }
    }
    suspend fun broken() {
        val contact = onDb { store.contact("x") } ?: return
        sendEphemeral(contact)
    }
    suspend fun alsoBroken() {
        update { onDb { store.message("y") } as Int }
    }
}
class Quiet {
    private lateinit var json: Json
    private fun parameterIsNotShadowed(preKeys: List<JsonValue>) {
        json.arr(preKeys)
    }
    private fun externalReceiver(bytes: ByteArray) {
        java.io.RandomAccessFile("f", "rw").use { handle -> handle.write(bytes) }
    }
    private fun builtinPassedToBuiltin(count: Long) {
        json.arr(listOf(JsonValue()))
    }
    private fun wantsLong(value: Long) {}
    private fun trailingPropertyIsUnknown() {
        // `store.contact(…)` is a Contact, but `.hash` is a String: the checker must not treat the
        // value as a Contact and report a type error that is not there.
        val size = store.contact("x")?.hash
        if (size != null) wantsLong(size)
    }
}
"""


def self_test() -> list[str]:
    """Both rules must fire on the two known bugs, and stay quiet on four look-alikes."""
    problems: list[str] = []
    decls = declarations({"selftest.kt": SELF_TEST})
    members = class_members_of(decls)
    properties = property_types({"selftest.kt": SELF_TEST})
    findings = check_file("selftest.kt", SELF_TEST, decls, members, properties)
    if not any("argument 1" in f and "Contact" in f for f in findings):
        problems.append("rule 1 did not fire on Contact passed where String is expected")
    if not any("non-suspend lambda" in f for f in findings):
        problems.append("rule 2 did not fire on a suspend call inside a plain lambda")
    if len(findings) != 2:
        problems.append(f"expected exactly 2 findings, got {len(findings)}: {findings}")
    return problems


def class_members_of(decls: dict[str, list[Fun]]) -> dict[str, set[str]]:
    """{Class: {member, …}} from the declarations this checker already parsed."""
    members: dict[str, set[str]] = {}
    for name, shapes in decls.items():
        for shape in shapes:
            if shape.owner:
                members.setdefault(shape.owner, set()).add(name)
    return members


def main() -> int:
    strict = "--strict" in sys.argv
    if "--self-test" in sys.argv or strict:
        problems = self_test()
        if problems:
            for problem in problems:
                print("  ✗ self-test:", problem)
            return 1
        print("self-test: both rules fire on their known bugs, look-alikes stay quiet")

    sources = read_sources()
    decls = declarations(sources)
    members = class_members_of(decls)
    properties = property_types(sources)
    findings: list[str] = []
    for path, source in sources.items():
        if "/build/" in path or ".selftest" in path:
            continue
        findings += check_file(path, source, decls, members, properties)

    print(f"scanned {len(sources)} files, {len(decls)} declared callables, "
          f"{len(CLASS_NAMES)} types")
    for finding in findings:
        print("  ✗", finding)
    print(f"{len(findings)} finding(s)")
    if strict and findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
