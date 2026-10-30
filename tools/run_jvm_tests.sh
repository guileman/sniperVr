#!/usr/bin/env bash
# Compila el núcleo (core + game) y el arnés con el compilador Kotlin embebido de Gradle
# y ejecuta las pruebas; luego compara con la verificación en Python.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GL="${GRADLE_LIB:-/opt/gradle/lib}"
CP="$(ls $GL/kotlin-compiler-embeddable-*.jar $GL/kotlin-stdlib-*.jar $GL/kotlin-reflect-*.jar $GL/kotlin-daemon-embeddable-*.jar $GL/trove4j-*.jar $GL/annotations-*.jar $GL/kotlinx-coroutines-core-jvm-*.jar 2>/dev/null | tr '\n' ':')"
OUT="$(mktemp -d)"
SRC="$ROOT/app/src/main/java/com/guileman/snipervr"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$(ls $GL/kotlin-stdlib-*.jar)" -d "$OUT" \
  "$SRC/core" "$SRC/game" "$ROOT/tools/jvmtest/Harness.kt" 2>&1 | grep -v "^warning" || true
java -cp "$OUT:$(ls $GL/kotlin-stdlib-*.jar)" HarnessKt "$OUT/kotlin_table.csv"
python3 "$ROOT/tools/verify_ballistics.py" --kotlin "$OUT/kotlin_table.csv"
