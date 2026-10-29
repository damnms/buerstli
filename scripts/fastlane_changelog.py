#!/usr/bin/env python3
"""Turns a CHANGELOG.md section into the plain-text "what's new" that F-Droid and Obtainium
show (fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt, max. 500 characters).

Usage: fastlane_changelog.py < section.md > 12.txt
"""
import re
import sys

LIMIT = 500
MORE = "More: https://github.com/damnms/buerstli/blob/main/CHANGELOG.md"


def _plain(line: str) -> str:
    line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)  # [text](url) -> text
    line = re.sub(r"\*\*([^*]+)\*\*", r"\1", line)        # **bold**
    line = re.sub(r"`([^`]+)`", r"\1", line)              # `code`
    return line


def to_store_text(markdown: str) -> str:
    lines: list[str] = []
    for raw in markdown.splitlines():
        if raw.startswith("### "):
            if lines and lines[-1] != "":
                lines.append("")
            lines.append(raw[4:].strip() + ":")
        elif raw.startswith("  ") and lines and lines[-1]:
            lines[-1] += " " + raw.strip()  # wrapped continuation of the previous line
        elif raw.strip() == "":
            if lines and lines[-1] != "":
                lines.append("")
        else:
            lines.append(raw.rstrip())
    while lines and lines[-1] == "":
        lines.pop()
    lines = [_plain(l) for l in lines]

    text = "\n".join(lines)
    if len(text) <= LIMIT:
        return text
    # Too long: keep whole lines only, then point to the full changelog.
    kept: list[str] = []
    for line in lines:
        candidate = "\n".join(kept + [line, "", MORE])
        if len(candidate) > LIMIT:
            break
        kept.append(line)
    while kept and (kept[-1] == "" or kept[-1].endswith(":")):
        kept.pop()  # don't end on an empty heading
    return "\n".join(kept + ["", MORE])


if __name__ == "__main__":
    sys.stdout.write(to_store_text(sys.stdin.read()) + "\n")
