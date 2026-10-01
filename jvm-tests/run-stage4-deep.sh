#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$OUT" $(find ../app/src/main/java/com/neonhud/app/core -name '*.java') $(find src -name '*.java')
java -Dfile.encoding=UTF-8 -cp "$OUT" tests.Stage4Execution5000Test
