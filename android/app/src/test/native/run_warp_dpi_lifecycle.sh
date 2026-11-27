#!/usr/bin/env bash
set -euo pipefail

test_dir="$(cd "$(dirname "$0")" && pwd)"
main_dir="$test_dir/../../main"
native_dir="$main_dir/cpp/warp_dpi"
catalog="$main_dir/java/io/nekohasekai/sagernet/generator/warp/DpiStrategyCatalog.kt"
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT

python3 - "$catalog" "$main_dir/assets/generator/byedpi-manager/strategies.tsv" > "$output/warp_dpi_test_strategies.h" <<'PY'
import json
from pathlib import Path
import re
import shlex
import sys

presets = re.findall(r'preset\("[^"]+", "([^"]*)"\)', Path(sys.argv[1]).read_text())
assert len(presets) == 18
imported = [line.split("\t", 1)[1] for line in Path(sys.argv[2]).read_text().splitlines()]
assert len(imported) == 60
print("static struct { int length; char **argv; } strategies[] = {")
for preset in presets + imported:
    argv = shlex.split(preset)
    args = ", ".join([*map(json.dumps, argv), "NULL"])
    print(f"    {{{len(argv)}, (char *[]){{{args}}}}},")
print("};")
PY

"${CC:-cc}" -D_GNU_SOURCE -std=c11 -O1 -g -pthread -Wl,--wrap=getaddrinfo,--wrap=connect,--wrap=getsockopt,--wrap=create_conn,--wrap=send \
    -I"$output" -I"$native_dir" \
    "$test_dir/warp_dpi_lifecycle.c" "$native_dir/warp_dpi_engine.c" \
    "$native_dir/warp_dpi_arguments.c" "$native_dir/private_stream.c" "$native_dir"/byedpi/*.c \
    -o "$output/warp_dpi_lifecycle"
"$output/warp_dpi_lifecycle"
