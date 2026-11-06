#!/usr/bin/env python3
"""On a failed CI run, turn the important parts of the build logs into GitHub annotations."""
import os
import re
import sys

PATTERNS = re.compile(
    r"(^e: |error:|FAILURE|What went wrong|Traceback|Exception|SystemExit|"
    r"could not|Could not|Unresolved|Execution failed|BUILD FAILED|^\s*> )"
)


def esc(s):
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def main():
    emitted = 0
    for path in sys.argv[1:]:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = [l.rstrip("\n") for l in f]
        hits = [l for l in lines if PATTERNS.search(l)]
        # Individual compiler errors first, they are the most useful.
        for l in [h for h in hits if h.startswith("e: ")][:30]:
            print(f"::error title={os.path.basename(path)}::{esc(l[:1500])}")
            emitted += 1
        summary = "\n".join(hits[:120])
        if summary:
            print(f"::error title={os.path.basename(path)} matches::{esc(summary[-20000:])}")
            emitted += 1
        tail = "\n".join(lines[-150:])
        print(f"::error title={os.path.basename(path)} tail::{esc(tail[-20000:])}")
        emitted += 1
    if not emitted:
        print("::error title=ci_report::no logs found")


if __name__ == "__main__":
    main()
