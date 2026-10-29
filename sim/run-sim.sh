#!/bin/bash
# 大师模式仿真 harness 编译+运行脚本（纯 JVM，不需要 Android SDK / Gradle）
# 用法: ./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [w0,w1,...] [baseSeed]
# 示例: ./sim/run-sim.sh 240 50 150000 3 0,400,600,800,1000
set -uo pipefail
cd "$(dirname "$0")/.."

OUT=/tmp/doudizhu-sim
mkdir -p "$OUT"

echo "=== 编译仿真 harness ==="
# 优先用 kotlinc；没有则回退到 Gradle 自带的 kotlin-compiler-embeddable。
# 注意：kotlin 2.0.x 编译器无法解析 JDK 21+ 的版本号，需用 JDK 17 调编译器。
GRADLE_LIB=$(ls -d /root/.local/share/mise/installs/gradle/*/gradle-*/lib 2>/dev/null | head -1)
JDK17=$(ls -d /root/.local/share/mise/installs/java/17 2>/dev/null | head -1)
[ -n "$JDK17" ] && export JAVA_HOME="$JDK17" PATH="$JDK17/bin:$PATH"
if command -v kotlinc >/dev/null 2>&1; then
    kotlinc \
        app/src/main/java/com/doudizhu/game/model/*.kt \
        app/src/main/java/com/doudizhu/game/logic/MasterSearch.kt \
        sim/SimMain.kt \
        -d "$OUT/classes" || { echo "❌ 编译失败"; exit 1; }
    RUN_CP="$OUT/classes"
else
    [ -n "$GRADLE_LIB" ] || { echo "❌ 找不到 kotlinc 或 kotlin-compiler-embeddable"; exit 1; }
    java -cp "$GRADLE_LIB/*" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        app/src/main/java/com/doudizhu/game/model/*.kt \
        app/src/main/java/com/doudizhu/game/logic/MasterSearch.kt \
        sim/SimMain.kt \
        -cp "$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" -no-reflect \
        -d "$OUT/classes" || { echo "❌ 编译失败"; exit 1; }
    RUN_CP="$OUT/classes:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar"
fi

echo "=== 运行仿真 ==="
java -cp "$RUN_CP" com.doudizhu.sim.SimMain "$@"
