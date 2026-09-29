#!/usr/bin/env python3
"""Plans the fast-lane matrix: the modules a push changed, plus every module that depends on them.

Usage: affected_modules.py <base-ref> <head-ref>

Prints a JSON list of {"module", "goal", "tests"} entries for a GitHub Actions matrix. An empty or unresolvable base
selects every module, as does a change to a file that affects the whole build.
"""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# What each module runs in the fast lane. A Surefire -Dtest filter, "" for every test, or None to compile the tests
# without running them because they all need Docker. Unlisted modules are compile-only until they get an entry.
FAST_TESTS = {
    "payment-service": "*UnitTest,*ConsumerContractTest",
    "ledger-service": "*UnitTest,*ConsumerContractTest",
    "auth-starter": "",
    "authorization-service": None,
}

# Changes to these can affect every module; entries ending in '/' are directories
BUILD_WIDE_PATHS = ("pom.xml", "mvnw", ".mvn/", ".github/workflows/push-checks.yml", ".github/scripts/")

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom(path):
    return ET.parse(path).getroot()


def reactor_modules():
    return [m.text.strip() for m in pom("pom.xml").findall("m:modules/m:module", NS)]


def artifact_id(module):
    return pom(Path(module, "pom.xml")).findtext("m:artifactId", namespaces=NS).strip()


def dependency_ids(module):
    root = pom(Path(module, "pom.xml"))
    return {d.findtext("m:artifactId", namespaces=NS).strip() for d in root.findall("m:dependencies/m:dependency", NS)}


def changed_files(base, head):
    if not base or set(base) == {"0"}:
        return None

    result = subprocess.run(["git", "diff", "--name-only", base, head], capture_output=True, text=True)
    return result.stdout.splitlines() if result.returncode == 0 else None


def is_build_wide(path):
    return any(path.startswith(p) if p.endswith("/") else path == p for p in BUILD_WIDE_PATHS)


def affected(modules, files):
    if files is None or any(is_build_wide(f) for f in files):
        return set(modules)

    selected = {m for m in modules if any(f.startswith(m + "/") for f in files)}

    # Close over dependents: a change to a library must re-test every module that uses it, directly or not
    ids = {m: artifact_id(m) for m in modules}
    deps = {m: dependency_ids(m) for m in modules}
    grew = True
    while grew:
        dependents = {m for m in modules if m not in selected and any(ids[s] in deps[m] for s in selected)}
        selected |= dependents
        grew = bool(dependents)

    return selected


def main():
    base, head = sys.argv[1], sys.argv[2]
    modules = reactor_modules()
    selected = affected(modules, changed_files(base, head))

    matrix = []
    for module in modules:
        if module not in selected:
            continue
        tests = FAST_TESTS.get(module)
        matrix.append({
            "module": module,
            "goal": "test-compile" if tests is None else "test",
            "tests": tests or "",
        })

    print(json.dumps(matrix))


if __name__ == "__main__":
    main()
