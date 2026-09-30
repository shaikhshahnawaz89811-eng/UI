#!/usr/bin/env bash
# Compiles the pure-Java core (no Android needed) plus the tests and runs them.
# Usage: ./run-tests.sh            (from anywhere)
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
CORE="../app/src/main/java/com/neonhud/app/core"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$OUT" $(find "$CORE" -name '*.java') $(find src -name '*.java')
java -Dfile.encoding=UTF-8 -cp "$OUT" tests.AllTests "$@"
