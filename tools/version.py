"""Resolve an Android/Windows build version from Git release tags."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time


TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)(?:\.(0|[1-9][0-9]?))?")


def resolve_tag(release, platform):
    explicit = os.environ.get('RELEASE_VERSION', '').strip()
    if explicit:
        return explicit
    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        return os.environ["GITHUB_REF_NAME"]
    patterns = [f"{platform}-v[0-9]*"]
    if not release:
        patterns.append("v[0-9]*")  # Legacy tags before platform-specific releases.
    for pattern in patterns:
        command = ["git", "describe", "--tags", "--abbrev=0", "--match", pattern]
        if release:
            command.append("--exact-match")
        try:
            return subprocess.check_output(command, text=True, stderr=subprocess.DEVNULL).strip()
        except subprocess.CalledProcessError:
            pass
    if release:
        raise SystemExit(f"Release requires a {platform}-vMAJOR.MINOR[.PATCH] tag")
    return "v2.0"  # Shared native preview before the first platform release.


def versions(tag, platform="android", release=False):
    prefix = f"{platform}-"
    if tag.startswith(prefix):
        tag = tag[len(prefix):]
    elif release:
        raise SystemExit(f"Expected {platform}-vMAJOR.MINOR[.PATCH]")
    match = TAG.fullmatch(tag)
    if not match:
        raise SystemExit(f"Invalid release tag {tag!r}; expected vMAJOR.MINOR[.PATCH]")
    major, minor, patch = (int(part or 0) for part in match.groups())
    # Preserve upgrade ordering over the previous preview's versionCode 20100.
    code = 20101 + major * 10000 + minor * 100 + patch
    if code > 2100000000 or major > 65535 or (platform == 'windows' and major > 255):
        raise SystemExit("Version exceeds the supported Android or Windows range")
    return tag[1:], f"{major}.{minor}.{patch}", code


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--platform", choices=["android", "windows"], default="android")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--ci-build-code", action="store_true", help="Generate a monotonically increasing CI versionCode")
    args = parser.parse_args()
    name, semver, code = versions(resolve_tag(args.release, args.platform), args.platform, args.release)
    if args.ci_build_code:
        # Seconds since 2020-01-01 UTC; stays below Android's versionCode limit until 2086.
        # Shared across Preview and Release workflows, unlike GITHUB_RUN_NUMBER.
        code = int(time.time()) - 1577836800
        if not (1 <= code <= 2100000000):
            raise SystemExit("CI build timestamp is outside the Android versionCode range")
    if args.json:
        print(json.dumps(dict(name=name, semver=semver, code=code)))
        raise SystemExit
    print(f"{args.platform} {name} (semver {semver}, code {code})")
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            print(f"name={name}\nsemver={semver}\ncode={code}", file=output)
