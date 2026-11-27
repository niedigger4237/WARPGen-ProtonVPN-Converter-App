#!/usr/bin/env python3
"""Run the platform build, switching Central endpoints once on transient dependency errors."""
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
TASKS = {
    "android": [":app:testReleaseUnitTest", ":app:assembleRelease"],
    "desktop": ["test", "portableZip"],
}
TRANSIENT = re.compile(
    r"Received status code (?:429|500|502|503|504)\b|429 Too Many Requests|"
    r"Read timed out|Connection timed out|Connection reset|Temporary failure in name resolution",
    re.IGNORECASE,
)
RESOLUTION = re.compile(
    r"Could not (?:resolve|GET|HEAD|get resource|download)|"
    r"Could not resolve all (?:files|artifacts)|Could not transfer artifact",
    re.IGNORECASE,
)


def build(platform, mirror):
    wrapper = ["cmd.exe", "/d", "/c", r"android\gradlew.bat"] if os.name == "nt" else ["./android/gradlew"]
    cmd = wrapper + ["-p", platform, *TASKS[platform], "--console=plain", "--stacktrace"]
    env = dict(os.environ, WARP_MAVEN_MIRROR=mirror)
    print(f"Gradle {platform}: Maven Central source = {mirror}", flush=True)
    transient = resolution = False
    with subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as proc:
        for line in proc.stdout:
            print(line, end="", flush=True)
            transient |= bool(TRANSIENT.search(line))
            resolution |= bool(RESOLUTION.search(line))
        return proc.wait(), transient and resolution


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in TASKS:
        raise SystemExit("Usage: gradle_ci.py android|desktop")
    platform = sys.argv[1]
    code, retry = build(platform, "google")
    if code and retry:
        print("Transient dependency resolution error; switching once to Maven Central.", flush=True)
        code, _ = build(platform, "central")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
