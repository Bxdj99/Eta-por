#!/bin/sh
# 台账内核（LedgerCore）的离线契约测试。
#
# 为什么不用 gradle test：这台机器离网，Robolectric / room-testing 拉不到，
# :app:testDebugUnitTest 直接失败在依赖解析。所以把内核抽成纯 JVM 层，
# 这里用本地 kotlinc（从 gradle 缓存里取）编译 + 跑真断言。
#
# 用法：sh tools/ledger-check/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../app/src/main/kotlin/io/github/mangi/eta/agent/tool/LedgerCore.kt"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

KSTD=$(find /root/.gradle/caches -name 'kotlin-stdlib-2.4.20.jar' | head -1)
KC=$(find /root/.gradle/caches -name 'kotlin-compiler-embeddable-2.4.20.jar' | head -1)
COR=$(find /root/.gradle/caches -name 'kotlinx-coroutines-core-jvm-1.8.0.jar' | head -1)
ANN=$(find /root/.gradle/caches -name 'annotations-13.0.jar' | head -1)
AJ=${ANDROID_JAR:-$(ls /workspace/android-sdk/platforms/android-37/android.jar)}

mkdir -p "$WORK/cls"
javac -d "$WORK/cls" "$HERE/org/json/"*.java
cp "$SRC" "$HERE/Main.kt" "$WORK/"
cd "$WORK"
java -cp "$KC:$KSTD:$COR:$ANN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -cp "$KSTD:$AJ:cls" LedgerCore.kt Main.kt -d out.jar 2>&1 | grep -E "error" && exit 1
java -cp "out.jar:cls:$KSTD" MainKt
