#!/usr/bin/env python3
"""Reads the numbers out of the JUnit result XML, and refuses to read stale ones.

WHY NOT THE GRADLE LOG: the log says "BUILD SUCCESSFUL" for a test task that was UP-TO-DATE and ran
nothing at all, and it says "5 tests completed, 3 failed" in a form that changes between versions.
The XML is written by the test task itself and carries one element per test, so the count and the
names of the failures both come from the same place.

WHY THE FRESHNESS CHECK: an up-to-date test task leaves the PREVIOUS run's XML lying there. Reading
it would produce a full set of green numbers for a run that never happened -- which is exactly the
failure this whole exercise is about, one level up. Every file has to be newer than the marker file
written immediately before the build, or this exits non-zero and says so.

Usage: count-tests.py <results-dir> <marker-file> [label]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

results_dir, marker = sys.argv[1], sys.argv[2]
label = sys.argv[3] if len(sys.argv) > 3 else ""

marker_time = os.path.getmtime(marker)
files = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))
if not files:
    print(f"RESULT {label}: NO RESULT XML AT ALL in {results_dir} -- the test task did not run")
    sys.exit(2)

stale = [f for f in files if os.path.getmtime(f) < marker_time]
if stale:
    print(f"RESULT {label}: STALE XML, these are older than this build: {stale}")
    sys.exit(3)

tests = failures = errors = skipped = 0
red, green, skipped_names, messages, probe_lines = [], [], [], [], []
for f in files:
    suite = ET.parse(f).getroot()
    tests += int(suite.get("tests", 0))
    failures += int(suite.get("failures", 0))
    errors += int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))
    for out in suite.iter("system-out"):
        for line in (out.text or "").splitlines():
            if "SECRETSTORE-PROBE" in line:
                probe_lines.append(line.strip())
    for case in suite.iter("testcase"):
        name = f'{case.get("classname").split(".")[-1]}.{case.get("name")}'
        bad = case.find("failure")
        if bad is None:
            bad = case.find("error")
        if bad is not None:
            red.append(name)
            # The wording verbatim: a counter-check is only a counter-check if the message says
            # what broke, and a paraphrase in a report cannot be checked against anything.
            first = (bad.get("message") or "").strip().splitlines()
            messages.append(f'{name}: {first[0] if first else "(no message)"}')
        elif case.find("skipped") is not None:
            # Kept apart from green on purpose: a skipped test is not a passed one, and the whole
            # point of this exercise is that the two get confused.
            skipped_names.append(name)
        else:
            green.append(name)

print(f"RESULT {label}: files={len(files)} tests={tests} failures={failures} "
      f"errors={errors} skipped={skipped}")
print(f"RESULT {label}: red={len(red)} {sorted(red)}")
print(f"RESULT {label}: green={len(green)} {sorted(green)}")
print(f"RESULT {label}: skipped={len(skipped_names)} {sorted(skipped_names)}")
for m in messages:
    print(f"RED {label}: {m}")
for line in probe_lines:
    print(f"PROBE {label}: {line}")
