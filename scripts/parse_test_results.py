#!/usr/bin/env python3
"""Parse JUnit XML test results and emit ::error lines plus summary.

Used by the GitHub Actions workflow build-apk.yml so that failing test
names and messages appear in the Actions annotations view.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    globs = sys.argv[1:] if len(sys.argv) > 1 else ["**/TEST-*.xml"]
    if not globs:
        print("no glob patterns provided", file=sys.stderr)
        return 2
    errs = []
    counts = {"total": 0, "failures": 0, "errors": 0, "skipped": 0}
    for pat in globs:
        for f in sorted(glob.glob(pat, recursive=True)):
            try:
                root = ET.parse(f).getroot()
            except Exception:
                continue
            counts["total"] += int(root.get("tests", 0))
            counts["failures"] += int(root.get("failures", 0))
            counts["errors"] += int(root.get("errors", 0))
            counts["skipped"] += int(root.get("skipped", 0))
            for tc in root.iter("testcase"):
                for fl in list(tc.findall("failure")) + list(tc.findall("error")):
                    cls = tc.get("classname", "")
                    name = tc.get("name", "")
                    msg = (fl.get("message") or fl.text or "").strip()
                    msg = msg.replace("\n", " ").replace("\r", " ")
                    errs.append((f, cls, name, msg[:900]))
    for (f, cls, name, msg) in errs:
        sys.stdout.write(f"::error file={f}::{cls}.{name} failed: {msg}\n")
    print(f"=== {len(errs)} test failure(s) ===")
    for (f, cls, name, msg) in errs:
        print(f"FAIL {cls}.{name}: {msg[:500]}")
    print(
        f"TOTAL: {counts['total']} tests, {counts['failures']} failures, "
        f"{counts['errors']} errors, {counts['skipped']} skipped"
    )
    return 1 if (counts["failures"] or counts["errors"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
