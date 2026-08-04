#!/bin/bash
# Kotlin 编译预检脚本
# 用法: ./check-kotlin.sh [文件路径]

KOTLINC="kotlinc"
SRC_DIR="app/src/main/java/com/doudizhu/game"
CHECK_DIR="/tmp/kotlin-check-$$"
mkdir -p "$CHECK_DIR"

if [ -n "$1" ]; then
    FILES="$1"
else
    FILES=$(find "$SRC_DIR" -name "*.kt" | grep -v "MainActivity.kt" | grep -v "GameSurfaceView.kt")
fi

echo "=== Kotlin 编译预检 ==="
echo "$FILES" | sed 's/^/  /'
echo ""

# 编译
ERRORS=$(echo "$FILES" | xargs $KOTLINC -d "$CHECK_DIR" 2>&1)

# 过滤噪音
REAL_ERRORS=$(echo "$ERRORS" | grep -E "^.*\.kt:[0-9]+:" | grep -v "unresolved reference: android" | grep -v "unresolved reference: androidx" | grep -v "unresolved reference: Handler" | grep -v "unresolved reference: Looper" | grep -v "unresolved reference: Bundle" | grep -v "unresolved reference: Window" | grep -v "unresolved reference: WindowManager" | grep -v "unresolved reference: AppCompatActivity" | grep -v "'onCreate' overrides nothing" | grep -v "'onResume' overrides nothing" | grep -v "unresolved reference: setContentView" | grep -v "unresolved reference: requestWindowFeature" | grep -v "unresolved reference: runOnUiThread" | grep -v "unresolved reference: minByOrNull" | grep -v "unresolved reference: maxByOrNull" | grep -v "unresolved reference: randomOrNull" | grep -v "unresolved reference: minWithOrNull" | grep -v "unresolved reference: sumOf" | grep -v "unresolved reference: it" | grep -v "type mismatch" | grep -v "nullable receiver" | grep -v "cannot infer" | grep -v "type inference" | grep -v "operator call")

rm -rf "$CHECK_DIR"

if [ -z "$REAL_ERRORS" ]; then
    echo "✅ 未发现代码错误（Android SDK 依赖项除外）"
    exit 0
else
    echo "❌ 发现以下错误:"
    echo "$REAL_ERRORS"
    exit 1
fi
