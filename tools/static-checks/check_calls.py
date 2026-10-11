#!/usr/bin/env python3
"""Argument-level call checking for calls to functions this project declares itself.

`check_symbols.py` proves that a member or constructor *exists*. It says nothing about the shape of
the call, which is where a compiler error usually comes from when a large file is edited without
being compiled: one argument too many, a named argument that was renamed, a required parameter
left out.

Only calls to functions declared in this repository are checked (a call is checked when its name
resolves to a declaration in the same package or to an explicit import). Everything else — the
standard library, Android, Compose, OkHttp — is left alone, so no stubs are needed and there are
no false positives from third-party signatures.

    python3 tools/static-checks/check_calls.py            # report
    python3 tools/static-checks/check_calls.py --strict   # non-zero exit on findings
"""

from __future__ import annotations

import glob
import os
import re
import sys
from dataclasses import dataclass, field

from check_symbols import mask_literals, read_sources

KEYWORDS = {
    "if", "for", "while", "when", "catch", "return", "object", "class", "fun", "val", "var", "try",
    "else", "do", "throw", "super", "this", "is", "in", "as", "typeof", "synchronized", "with",
    "repeat", "require", "check", "listOf", "mapOf", "setOf", "arrayOf", "emptyList", "emptyMap",
    "mutableListOf", "mutableMapOf", "let", "run", "apply", "also", "use", "useLines", "error",
    "checkNotNull", "requireNotNull", "lazy", "println", "print", "TODO", "resultOf", "applyIf",
}


@dataclass
class Param:
    name: str
    has_default: bool
    vararg: bool = False


@dataclass
class Signature:
    name: str
    params: list[Param]
    suspend: bool
    file: str
    line: int

    @property
    def required(self) -> int:
        return sum(1 for p in self.params if not p.has_default and not p.vararg)

    @property
    def maximum(self) -> int:
        return 10_000 if any(p.vararg for p in self.params) else len(self.params)

    def describe(self) -> str:
        shape = ", ".join(
            ("vararg " if p.vararg else "") + p.name + ("?" if p.has_default else "") for p in self.params
        )
        return f"{self.name}({shape})"


def split_top_level(text: str) -> list[str]:
    """Splits on commas that are not inside brackets, quotes or angle brackets."""
    parts, depth, current, quote = [], 0, [], None
    for char in text:
        if quote:
            current.append(char)
            if char == quote:
                quote = None
            continue
        if char in "\"'":
            quote = char
            current.append(char)
            continue
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
            continue
        current.append(char)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return [p.strip() for p in parts if p.strip()]


def parse_params(text: str) -> list[Param]:
    params: list[Param] = []
    for chunk in split_top_level(text):
        chunk = chunk.strip()
        if not chunk:
            continue
        vararg = chunk.startswith("vararg ")
        chunk = chunk.removeprefix("vararg ")
        # Annotations on parameters are ignored: @Foo val x: Int = 1
        chunk = re.sub(r"^(@\w+(?:\([^)]*\))?\s*)+", "", chunk)
        # Visibility and modifiers are ignored too: `private val value: JsonValue.Obj` is a
        # parameter like any other. Missing this made constructors look like they took no arguments.
        chunk = re.sub(
            r"^(?:(?:public|private|internal|protected|override|open|final|lateinit|vararg)\s+)*",
            "",
            chunk,
        )
        name_match = re.match(r"(?:val |var |crossinline |noinline )*(\w+)\s*:", chunk)
        if not name_match:
            continue
        has_default = bool(re.search(r"=\s*\S", chunk.split(":", 1)[-1]))
        params.append(Param(name_match.group(1), has_default, vararg))
    return params


def balanced(text: str, start: int) -> tuple[str, int]:
    """Returns the content of the bracket group starting at `start` and the index after it."""
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


def declarations(sources: dict[str, str]) -> dict[str, list[Signature]]:
    """All functions and primary constructors declared in the project, by simple name."""
    found: dict[str, list[Signature]] = {}

    def add(name: str, params: list[Param], suspend: bool, path: str, line: int) -> None:
        found.setdefault(name, []).append(Signature(name, params, suspend, os.path.basename(path), line))

    for path, source in sources.items():
        raw = source
        masked = mask_literals(source)
        for match in re.finditer(
            r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*\n[ \t]*)*"
            r"(?P<mods>(?:(?:public|private|internal|protected|open|override|inline|suspend|operator|infix|tailrec|external|abstract|actual|expect)\s+)*)"
            r"fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,\s]+?\.)?(?P<name>\w+)\s*\(",
            masked,
            re.M,
        ):
            # Offsets are identical in the masked and raw source, so the parameter list is read
            # from the raw text: a default value like = "" is blanked out by the mask, which would
            # otherwise look like a required parameter.
            body, _ = balanced(raw, match.end() - 1)
            line = masked[:match.start()].count("\n") + 1
            add(match.group("name"), parse_params(body), "suspend" in match.group("mods"), path, line)
        for match in re.finditer(r"^[ \t]*(?:data |sealed |abstract |open |value |inline )*class\s+(\w+)\s*(?:<[^>]*>)?\s*\(", masked, re.M):
            body, _ = balanced(raw, match.end() - 1)
            line = masked[:match.start()].count("\n") + 1
            add(match.group(1), parse_params(body), False, path, line)
        # Secondary constructors: `class JsonObject(…) { constructor(text: String) : … }`. Without
        # them a class looks like it takes fewer arguments than it does.
        for match in re.finditer(r"\bconstructor\s*\(", masked):
            owners = re.findall(r"\b(?:class|interface|object)\s+(\w+)", masked[:match.start()])
            if not owners:
                continue
            body, _ = balanced(raw, match.end() - 1)
            line = masked[:match.start()].count("\n") + 1
            add(owners[-1], parse_params(body), False, path, line)
    return found


def imports_of(source: str) -> set[str]:
    return {
        m.group(2) or m.group(1).split(".")[-1]
        for m in re.finditer(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", source, re.M)
    }


def check_file(path: str, source: str, package: str, all_decls: dict[str, list[Signature]],
               packages: dict[str, set[str]]) -> list[str]:
    findings: list[str] = []
    raw = source
    masked = mask_literals(source)
    imported = imports_of(source)
    in_package = packages.get(package, set())

    # Uppercase-initial names matter as much as lowercase ones: they are the constructors and
    # Compose composables, which is where a renamed or mistyped named argument usually hides.
    for match in re.finditer(r"(?<![\w.])([A-Za-z]\w*)\s*\(", masked):
        name = match.group(1)
        if name in KEYWORDS:
            continue
        # `fun name(` is a declaration, not a call
        before = masked[:match.start()]
        if re.search(r"\bfun\s+$", before) or re.search(r"\bfun\s+<[^>]*>\s*$", before):
            continue
        if re.search(r"\bfun\s+[\w.<>?,\s]*\.\s*$", before):
            continue  # extension function declaration
        # Only check names that resolve to something in this project.
        if name not in in_package and name not in imported:
            continue
        signatures = all_decls.get(name)
        if not signatures:
            continue
        # Private helpers with the same name live in several files (`SwitchRow`, `Section`, …), so a
        # project-wide "must be unique" rule would give up on exactly the calls that matter. A
        # declaration in this same file wins; only if there is none does the unique-shape rule apply.
        here = os.path.basename(path)
        local = [s for s in signatures if s.file == here]
        if local:
            signatures = local
        elif name in imported:
            # The file imports that simple name from a library — `Text` is Compose's composable,
            # not `Wire.Payload.Text`. Judging it with a project signature invents errors.
            continue
        shapes = {(s.required, s.maximum, tuple(p.name for p in s.params)) for s in signatures}
        if len(shapes) != 1:
            continue  # overloaded with different shapes: cannot decide from here

        # Masking keeps every offset, so the argument list can be read back from the raw source
        # (where string literals are still intact) at exactly the same positions.
        args_text, after = balanced(raw, match.end() - 1)
        arguments = [] if not args_text.strip() else split_top_level(args_text)
        named = {a.split("=", 1)[0].strip() for a in arguments if re.match(r"^\s*\w+\s*=[^=]", a)}
        positional = [a for a in arguments if a not in arguments or a.split("=", 1)[0].strip() not in named] if False else [
            a for a in arguments if not re.match(r"^\s*\w+\s*=[^=]", a)
        ]
        trailing_lambda = masked[after:after + 40].lstrip().startswith("{")

        signature = signatures[0]
        maximum = signature.maximum
        if trailing_lambda:
            maximum = max(0, maximum - 1)
        problems: list[str] = []
        if len(positional) > maximum:
            problems.append(f"{len(positional)} positional arguments, at most {maximum} expected")
        provided = len(positional) + len(named) + (1 if trailing_lambda else 0)
        if provided < signature.required:
            problems.append(f"only {provided} arguments, {signature.required} required")
        unknown = named - {p.name for p in signature.params}
        if unknown:
            problems.append("no parameter named " + ", ".join(sorted(unknown)))
        if problems:
            line = masked[:match.start()].count("\n") + 1
            findings.append(
                f"{os.path.basename(path)}:{line}: {name}(…) — " + "; ".join(problems)
                + f"  [{signature.file}:{signature.line} declares {signature.describe()}]"
            )
    return findings


def main() -> int:
    strict = "--strict" in sys.argv
    sources = read_sources()
    all_decls = declarations(sources)
    packages: dict[str, set[str]] = {}
    package_of: dict[str, str] = {}
    for path, source in sources.items():
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        if not package:
            continue
        package_of[path] = package.group(1)
        packages.setdefault(package.group(1), set()).update(
            re.findall(r"^\s*(?:private |internal |public |suspend |inline )*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,\s]+\.)?(\w+)\s*\(", source, re.M)
        )
    print(f"scanned {len(sources)} files, {len(all_decls)} declared callables")

    findings: list[str] = []
    for path, source in sources.items():
        if path in package_of:
            findings += check_file(path, source, package_of[path], all_decls, packages)

    for finding in findings:
        print("  ✗", finding)
    print(f"{len(findings)} finding(s)")
    if strict and findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
