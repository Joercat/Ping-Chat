#!/usr/bin/env python3
"""Static cross-checks for the Android sources when no Android SDK is available.

The Android modules cannot be compiled without the Android SDK and the Maven dependencies, so the
usual safety net is missing while editing. These checks are a cheap substitute and have caught
real mistakes (a method that does not exist on a manager class, a model field renamed in one
place only, an import pointing at a file that was deleted):

  1. every `import com.ping.app.…` resolves to something that is actually declared
  2. every member used on a known receiver (`controller.`, `graph.`, `store.`, `cryptoStore.`,
     `engine.`, `settings.`, `vault.`, `notifications.`, …) is declared on that type
  3. every `SomeType(...)` call refers to a declaration that exists somewhere in the project,
     or to something the file imported (external API)

Heuristic, but strict enough to be worth running after every edit:

    python3 tools/static-checks/check_symbols.py            # report
    python3 tools/static-checks/check_symbols.py --strict   # non-zero exit on findings
"""

from __future__ import annotations

import glob
import os
import re
import sys

ROOT = "app/src/main/java/com/ping/app"
RECEIVERS = {
    "graph": "AppGraph",
    "controller": "PingController",
    "store": "PingStore",
    "cryptoStore": "CryptoStore",
    "engine": "Engine",
    "identity": "IdentityManager",
    "settings": "Settings",
    "sessions": "SessionManager",
    "relay": "RelayClient",
    "lan": "LanTransport",
    "attachments": "AttachmentStore",
    "notifications": "Notifications",
    "vault": "Vault",
    "db": "Db",
}

# Members that come from the standard library, Android or the Kotlin/Compose surface rather than
# from our own declarations.
IGNORED_MEMBERS = {
    "value", "emit", "tryEmit", "collect", "collectLatest", "launch", "async", "cancel", "join",
    "map", "filter", "first", "firstOrNull", "last", "size", "isEmpty", "isNotEmpty", "getOrNull",
    "orEmpty", "let", "run", "also", "apply", "use", "copy", "toString", "hashCode", "equals",
    "name", "ordinal", "values", "valueOf", "iterator", "length", "indices", "get", "put", "add",
    "remove", "clear", "close", "trim", "split", "replace", "contains", "indexOf", "substring",
    "startsWith", "endsWith", "take", "plus", "minus", "forEach", "sorted", "sortedBy", "distinct",
    "count", "any", "all", "none", "toList", "toSet", "toMutableList", "joinToString", "reversed",
    "toInt", "toLong", "toByteArray", "toBase64Url", "toHex", "isActive", "incrementAndGet",
}

IGNORED_TYPES = {
    "String", "Int", "Long", "Boolean", "ByteArray", "Byte", "Char", "Float", "Double", "Short",
    "Any", "Unit", "List", "Set", "Map", "MutableList", "MutableMap", "Pair", "Triple", "Array",
    "ArrayList", "LinkedHashMap", "HashMap", "Exception", "IllegalStateException",
    "IllegalArgumentException", "RuntimeException", "Throwable", "Result", "Regex", "Thread",
    "StringBuilder", "Comparable", "Number", "File", "URL", "HttpURLConnection", "JsonObject",
    "JsonValue", "IntArray", "ByteBuffer", "SecureRandom", "KeyPair", "KeyPairGenerator", "Cipher",
    "Mac", "MessageDigest", "Signature", "KeyFactory", "KeyAgreement", "Provider", "SecretKey",
    "KeyStore", "KeyGenerator", "C", "Pair", "Nothing", "Enum", "Iterable", "Iterator",
}


def mask_literals(source: str) -> str:
    """Blanks out comments and string literals, keeping every newline so line numbers stay valid.

    Without this the checks below see SQL keywords and prose inside triple-quoted strings and
    reports them as unresolved symbols.
    """
    out = []
    index = 0
    length = len(source)
    while index < length:
        char = source[index]
        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            end = length if end == -1 else end + 3
            out.append(re.sub(r"[^\n]", " ", source[index:end]))
            index = end
        elif source.startswith("/*", index):
            end = source.find("*/", index + 2)
            end = length if end == -1 else end + 2
            out.append(re.sub(r"[^\n]", " ", source[index:end]))
            index = end
        elif source.startswith("//", index):
            end = source.find("\n", index)
            end = length if end == -1 else end
            out.append(" " * (end - index))
            index = end
        elif char in ('"', "'"):
            end = index + 1
            while end < length:
                if source[end] == "\\":
                    end += 2
                    continue
                if source[end] == char or source[end] == "\n":
                    break
                end += 1
            end = min(end + 1, length)
            out.append(re.sub(r"[^\n]", " ", source[index:end]))
            index = end
        else:
            out.append(char)
            index += 1
    return "".join(out)


def read_sources() -> dict[str, str]:
    sources = {}
    patterns = [
        os.path.join(ROOT, "**", "*.kt"),
        "app/src/test/java/com/ping/app/**/*.kt",
    ]
    for pattern in patterns:
        for path in glob.glob(pattern, recursive=True):
            sources[path] = open(path).read()
    return sources


def class_members(sources: dict[str, str]) -> dict[str, set[str]]:
    """{Class name: {member, ...}} for every class/object/enum in the project."""
    members: dict[str, set[str]] = {}

    def add(name: str, found: set[str]) -> None:
        members.setdefault(name, set()).update(found)

    for source in sources.values():
        for match in re.finditer(r"\b(?:class|interface|object|enum class)\s+([A-Z]\w+)\s*(?:\(([^)]*)\))?", source, re.S):
            name, params = match.group(1), match.group(2) or ""
            found = set(re.findall(r"\b(?:val|var)\s+(\w+)", params))
            body = source[match.end():match.end() + 100_000]
            found |= set(
                re.findall(
                    r"^\s*(?:override\s+|suspend\s+|private\s+|internal\s+|protected\s+|inline\s+|operator\s+|open\s+)*"
                    r"(?:fun|val|var)\s+(?:<[^>]*>\s*)?(\w+)",
                    body,
                    re.M,
                )
            )
            add(name, found)
        for match in re.finditer(r"\benum class\s+([A-Z]\w+)", source):
            add(match.group(1), set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\b", source, re.M)))
        for match in re.finditer(r"\bobject\s+([A-Z]\w+)", source):
            body = source[match.end():match.end() + 20_000]
            add(match.group(1), set(re.findall(r"^\s*(?:const\s+|private\s+)?(?:val|var|fun)\s+(\w+)", body, re.M)))
    return members


def package_members(sources: dict[str, str]) -> dict[str, set[str]]:
    """{package: {top-level name, ...}}"""
    packages: dict[str, set[str]] = {}
    for source in sources.values():
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        if not package:
            continue
        names = set(
            re.findall(
                r"^(?:@\w+(?:\([^)]*\))?\s*)*(?:private |internal |public |sealed |data |enum |abstract |open |inline |suspend |value )*"
                r"(?:fun|class|object|interface|typealias|val|var)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)",
                source,
                re.M,
            )
        )
        names |= set(re.findall(r"^\s*(?:private |internal |public |suspend |inline )*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(", source, re.M))
        packages.setdefault(package.group(1), set()).update(names)
    return packages


def check_imports(sources, packages) -> list[str]:
    findings = []
    for path, source in sources.items():
        for match in re.finditer(r"^import\s+(com\.ping\.app\.[\w.]+)", source, re.M):
            target = match.group(1)
            parts = target.split(".")
            resolved = False
            for cut in range(len(parts), 1, -1):
                if ".".join(parts[:cut - 1]) in packages and parts[cut - 1] in packages[".".join(parts[:cut - 1])]:
                    resolved = True
                    break
                if ".".join(parts[:cut]) in packages:
                    resolved = True
                    break
            if not resolved:
                findings.append(f"{os.path.basename(path)}: import {target} resolves to nothing")
    return findings


def check_members(sources, members) -> list[str]:
    findings = []
    for path, source in sources.items():
        source = mask_literals(source)
        for receiver, type_name in RECEIVERS.items():
            if not re.search(rf"\b(?:val|var|private val)\s+{receiver}\s*:\s*{type_name}\b", source):
                continue  # only check files where the receiver has a known type
            declared = members.get(type_name, set())
            for match in re.finditer(rf"\b{receiver}\.(\w+)", source):
                member = match.group(1)
                if member in declared or member in IGNORED_MEMBERS or member[0].isupper():
                    continue
                line = source[: match.start()].count("\n") + 1
                findings.append(f"{os.path.basename(path)}:{line}: {receiver} ({type_name}) has no member '{member}'")
    return findings


def enum_constants(sources) -> set[str]:
    """Every enum constant name in the project (they look like constructor calls to the check)."""
    found: set[str] = set()
    for source in sources.values():
        for match in re.finditer(r"\benum class\s+[A-Z]\w+[^{]*\{", source):
            body = source[match.end():match.end() + 1500]
            found |= set(re.findall(r"\b([A-Z][A-Z0-9_]{1,})\s*[,(;]", body))
    return found


def check_constructors(sources, members, packages) -> list[str]:
    findings = []
    enumerations = enum_constants(sources)
    for path, source in sources.items():
        raw = source
        source = mask_literals(source)
        # A wildcard import means the file's scope is unknowable without the real compiler, so the
        # constructor check cannot say anything useful about it.
        if re.search(r"^\s*import\s+[\w.]+?\.\*\s*$", raw, re.M):
            continue
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        package_name = package.group(1) if package else ""
        visible = set(members)
        visible |= packages.get(package_name, set())
        imported = set(re.findall(r"^\s*import\s+[\w.]+?(\w+)\s*(?:as\s+\w+)?\s*$", source, re.M))
        for match in re.finditer(r"(?<![\w.@])([A-Z]\w*)\s*\(", source):
            name = match.group(1)
            if name in IGNORED_TYPES or name in visible or name in imported or name in enumerations:
                continue
            line = source[: match.start()].count("\n") + 1
            findings.append(f"{os.path.basename(path)}:{line}: '{name}' is called but never declared or imported")
    return findings


# Types from the platform/JDK that always have to be imported (or be in the same package). Missing
# one of these is the classic "compiles in the IDE, fails in CI" mistake.
PLATFORM_TYPES = {
    "Context", "Intent", "Uri", "Log", "Build", "File", "Cursor", "Bundle", "Bitmap",
    "BitmapFactory", "Notification", "NotificationChannel", "NotificationManager", "PendingIntent",
    "Service", "BroadcastReceiver", "IntentFilter", "Handler", "Looper", "Color", "Application",
    "ContentResolver", "SharedPreferences", "KeyStore", "SecretKey", "Cipher", "Mac", "MessageDigest",
    "ByteBuffer", "InputStream", "OutputStream", "FileInputStream", "FileOutputStream", "Socket",
    "ServerSocket", "URL", "HttpURLConnection", "Properties", "UUID", "Date", "SimpleDateFormat",
    "Locale", "Random", "Collections", "Arrays", "CharBuffer", "CipherInputStream",
    "CipherOutputStream", "Activity", "ComponentName", "PackageManager", "WindowManager",
    "View", "Settings", "Environment", "Process", "SystemClock",
}

# Types that live in the same package as the file that uses them, or that Kotlin resolves without
# an import. Anything here is exempt from the check.
SAME_FILE_TYPES = {"R"}


def check_platform_imports(sources) -> list[str]:
    findings = []
    for path, source in sources.items():
        raw = source
        source = mask_literals(source)
        if re.search(r"^\s*import\s+[\w.]+?\.\*\s*$", raw, re.M):
            continue
        imported = set(re.findall(r"^\s*import\s+[\w.]+?(\w+)\s*(?:as\s+\w+)?\s*$", raw, re.M))
        declared = set(re.findall(r"\b(?:class|interface|object|enum class)\s+([A-Z]\w+)", source))
        for name in sorted(PLATFORM_TYPES):
            if name in imported or name in declared or name in SAME_FILE_TYPES:
                continue
            # Used as a type, a constructor or a static member. The lookbehind keeps fully
            # qualified uses (android.content.Context, java.security.MessageDigest) out of it —
            # those do not need an import.
            used = re.search(rf"(?<![\w.])(?:{name}\s*[(.:<]|{name}\b\s*\.)", source)
            if used:
                line = source[:used.start()].count("\n") + 1
                findings.append(f"{os.path.basename(path)}:{line}: '{name}' is used but not imported")
    return findings


def main() -> int:
    strict = "--strict" in sys.argv
    sources = read_sources()
    members = class_members(sources)
    packages = package_members(sources)
    print(f"scanned {len(sources)} Kotlin files, {len(members)} types, {len(packages)} packages")

    findings = []
    findings += check_imports(sources, packages)
    findings += check_members(sources, members)
    findings += check_constructors(sources, members, packages)
    findings += check_platform_imports(sources)

    for finding in findings:
        print("  ✗", finding)
    print(f"{len(findings)} finding(s)")
    if strict and findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
