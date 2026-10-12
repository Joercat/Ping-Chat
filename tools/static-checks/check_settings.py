#!/usr/bin/env python3
"""Checks that every field of `SettingsSnapshot` is actually loaded and saved.

`Settings` keeps its state in SharedPreferences, and it does so with explicit lists: `read()` names
every field, `persist()` writes every field. Adding a field to the data class and forgetting one of
those two lists is a silent bug — the switch in the UI flips, the value looks applied, and it is
back to the default after a restart. Nothing else in this repository would notice, so this checks
the three lists against each other, and the SharedPreferences key each field uses.

    python3 tools/static-checks/check_settings.py            # report
    python3 tools/static-checks/check_settings.py --strict   # non-zero exit on findings
"""

from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from check_symbols import ROOT  # noqa: E402

# ROOT is the package source root (…/app/src/main/java/com/ping/app).
SETTINGS_PATH = os.path.join(ROOT, "data", "Settings.kt")

SELF_TEST = """
class SettingsSnapshot(
    val alpha: Boolean = false,
    val beta: Long = 0L,
    val gamma: String = "no",
    val forgotten: Boolean = true
)

private fun read(): SettingsSnapshot = SettingsSnapshot(
    alpha = prefs.getBoolean("alpha", false),
    // An expression body containing a `when` block is what broke an earlier version of this
    // checker: everything after this point has to still be seen.
    gamma = when {
        prefs.getBoolean("gamma_flag", false) -> "yes"
        else -> prefs.getString("gamma", "no") ?: "no"
    },
    beta = prefs.getLong("beta", 0L)
)

private fun persist(snapshot: SettingsSnapshot) {
    prefs.edit()
        .putBoolean("alpha", snapshot.alpha)
        .putString("gamma", snapshot.gamma)
        .putLong("beta_key", snapshot.beta)
        .apply()
}
"""


def snapshot_fields(source: str) -> list[str]:
    """Field names of the data class that holds the settings."""
    match = re.search(r"(?:data\s+)?class SettingsSnapshot\s*\(", source)
    if not match:
        return []
    depth, index = 1, match.end()
    while index < len(source) and depth:
        if source[index] == "(":
            depth += 1
        elif source[index] == ")":
            depth -= 1
        index += 1
    body = source[match.end():index - 1]
    return re.findall(r"\bval\s+(\w+)\s*:", body)


def read_bindings(source: str) -> dict[str, str]:
    """{field: preference key} from `read()`."""
    body = member_text(source, "read")
    return {
        match.group(1): match.group(2)
        for match in re.finditer(r"(\w+)\s*=\s*prefs\.get\w+\(\"([^\"]+)\"", body)
    }


def persist_bindings(source: str) -> dict[str, str]:
    """{field: preference key} from `persist()`."""
    body = member_text(source, "persist")
    # `.putBoolean("alpha", snapshot.alpha)` -> field `alpha`, key `alpha`
    return {
        match.group(2): match.group(1)
        for match in re.finditer(r'\.put\w+\("([^"]+)",\s*snapshot\.(\w+)', body)
    }


MEMBER_RE = re.compile(r"^    (?:private |internal |public |protected |override |suspend |inline )*"
                       r"(?:fun|val|var|companion|object)\b", re.M)


def member_text(source: str, name: str) -> str:
    """Text of one class member, from its declaration to the next member.

    Counting braces does not work here: `read()` is an expression body whose first brace belongs to
    a `when` block, so a brace-matching reader stops inside the expression and silently loses every
    line after it. Class members are separated by the next line at the same indentation instead,
    which is exactly how this file is written.
    """
    start = source.find(f"fun {name}(")
    if start == -1:
        return ""
    # Back up to the start of the declaration line (its modifiers).
    line_start = source.rfind("\n", 0, start) + 1
    following = MEMBER_RE.search(source, source.find("\n", start) + 1)
    end = following.start() if following else len(source)
    return source[line_start:end]


def assigned_fields(source: str, member: str) -> set[str]:
    """Every field `field = …` assigned inside one member, whatever the expression looks like."""
    body = member_text(source, member)
    return set(re.findall(r"(?<![\w.])(\w+)\s*=", body))


def findings_for(source: str, label: str) -> list[str]:
    findings: list[str] = []
    fields = snapshot_fields(source)
    if not fields:
        return [f"{label}: no SettingsSnapshot data class found"]
    loaded = read_bindings(source)
    stored = persist_bindings(source)
    read_fields = assigned_fields(source, "read")
    for field in fields:
        if field not in read_fields:
            findings.append(f"{label}: `{field}` is never read in read() — it always comes back as the default")
        if field not in stored:
            findings.append(f"{label}: `{field}` is never written in persist() — changing it does not survive a restart")
        if field in loaded and field in stored and loaded[field] != stored[field]:
            findings.append(
                f"{label}: `{field}` is read from \"{loaded[field]}\" but written to "
                f"\"{stored[field]}\" — the value never comes back"
            )
    for field in loaded:
        if field not in fields:
            findings.append(f"{label}: read() loads `{field}`, which is not in SettingsSnapshot")
    for field in stored:
        if field not in fields:
            findings.append(f"{label}: persist() writes `{field}`, which is not in SettingsSnapshot")
    return findings


def self_test() -> list[str]:
    """The fixture has one unpersisted field and one mismatched key; both must be reported."""
    problems: list[str] = []
    findings = findings_for(SELF_TEST, "selftest")
    if not any("forgotten" in f and "persist()" in f for f in findings):
        problems.append("did not notice a field that is never written")
    if not any("beta" in f and "never comes back" in f for f in findings):
        problems.append("did not notice a field read and written under different keys")
    if any("alpha" in f for f in findings):
        problems.append(f"reported a field that is correct: {findings}")
    if any("gamma" in f for f in findings):
        problems.append(f"lost a field declared after a `when` block: {findings}")
    return problems


def main() -> int:
    strict = "--strict" in sys.argv
    if "--self-test" in sys.argv or strict:
        problems = self_test()
        if problems:
            for problem in problems:
                print("  ✗ self-test:", problem)
            return 1
        print("self-test: an unpersisted field and a mismatched key are both caught")

    if not os.path.exists(SETTINGS_PATH):
        print(f"  ✗ {SETTINGS_PATH} is missing")
        return 1
    with open(SETTINGS_PATH) as handle:
        source = handle.read()
    findings = findings_for(source, os.path.basename(SETTINGS_PATH))
    fields = snapshot_fields(source)
    print(f"settings: {len(fields)} fields, {len(read_bindings(source))} read, {len(persist_bindings(source))} written")
    for finding in findings:
        print("  ✗", finding)
    print(f"{len(findings)} finding(s)")
    if strict and findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
