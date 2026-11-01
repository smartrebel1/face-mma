#!/usr/bin/env python3
"""Compact, greppable dump of the Android lint XML report for CI logs in git."""
import os
import sys
import xml.etree.ElementTree as ET

CANDIDATES = [
    "app/build/reports/lint-results-debug.xml",
    "app/build/reports/lint-results.xml",
]

path = next((p for p in CANDIDATES if os.path.exists(p)), None)
if path is None:
    print("no lint report found")
    sys.exit(0)

try:
    root = ET.parse(path).getroot()
except Exception as exc:  # noqa: BLE001
    print("cannot parse lint report:", exc)
    sys.exit(0)

issues = root.findall("issue")
counts = {}
rows = []
for issue in issues:
    sev = issue.get("severity") or "?"
    counts[sev] = counts.get(sev, 0) + 1
    loc = issue.find("location")
    f = (loc.get("file") if loc is not None else "") or ""
    line = (loc.get("line") if loc is not None else "") or ""
    f = f.split("/scan/")[-1]
    rows.append(
        "{}\t{}\t{}:{}\t{}".format(
            sev, issue.get("id"), f, line, (issue.get("message") or "").replace("\n", " ")
        )
    )

print("lint issues:", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "none")
print("---")
for row in rows:
    if row.startswith("Error") or row.startswith("Fatal") or row.startswith("Warning"):
        print(row)
