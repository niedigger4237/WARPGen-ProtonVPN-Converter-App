"""Resolve an Android/Windows build version from Git release tags."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time


TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)(?:\.(0|[1-9][0-9]?))?")


def resolve_tag(release):
    explicit = os.environ.get('RELEASE_VERSION', '').strip()
    if explicit:
        return explicit
    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        return os.environ["GITHUB_REF_NAME"]
    command = ["git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*"]
    if release:
        command.append("--exact-match")
    try:
        return subprocess.check_output(command, text=True, stderr=subprocess.DEVNULL).strip()
    except subprocess.CalledProcessError:
        if release:
            raise SystemExit("Release requires a version tag on the selected commit")
        return "v1.0"  # Preview before the first release tag.


def versions(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise SystemExit(f"Invalid release tag {tag!r}; expected vMAJOR.MINOR[.PATCH]")
    major, minor, patch = (int(part or 0) for part in match.groups())
    # Preserve upgrade ordering over the previous preview's versionCode 20100.
    code = 20101 + major * 10000 + minor * 100 + patch
    if code > 2100000000 or major > 65535:
        raise SystemExit("Version exceeds the supported Android or Windows range")
    return tag[1:], f"{major}.{minor}.{patch}", code


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--ci-build-code", action="store_true", help="Generate a monotonically increasing CI versionCode")
    args = parser.parse_args()
    name, semver, code = versions(resolve_tag(args.release))
    if args.ci_build_code:
        # Seconds since 2020-01-01 UTC; stays below Android's versionCode limit until 2086.
        # Shared across Preview and Release workflows, unlike GITHUB_RUN_NUMBER.
        code = int(time.time()) - 1577836800
        if not (1 <= code <= 2100000000):
            raise SystemExit("CI build timestamp is outside the Android versionCode range")
    if args.json:
        print(json.dumps(dict(name=name, semver=semver, code=code)))
        raise SystemExit
    print(f"Android {name} ({code}), Windows {semver}")
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            print(f"name={name}\nsemver={semver}\ncode={code}", file=output)
