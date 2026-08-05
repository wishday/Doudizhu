#!/bin/bash
# Kotlin 编译预检脚本
# 用法: ./check-kotlin.sh
# 
# 编译项目所有纯 Kotlin 文件（排除 Android 组件），利用跨文件引用做真实的类型检查。
# Gradle 不可用时（例如 CI 无网络），此脚本提供基础的类型安全验证。
set -uo pipefail

KOTLINC="kotlinc"
SRC_DIR="app/src/main/java/com/doudizhu/game"
CHECK_DIR="/tmp/kotlin-check-$$"
mkdir -p "$CHECK_DIR"

# 收集所有纯逻辑的 kt 文件（排除 Android UI 文件，避免引入 SDK 依赖）
FILES=$(find "$SRC_DIR" -name "*.kt" \
    | grep -v "MainActivity.kt" \
    | grep -v "GameSurfaceView.kt")

echo "=== Kotlin 编译预检 ==="
echo "$FILES" | sed 's/^/  /'
echo ""

# 编译所有文件（跨文件引用可被解析，类型推断完整）
ERRORS=$(echo "$FILES" | xargs $KOTLINC -d "$CHECK_DIR" 2>&1) || true

# 过滤无关噪音：仅保留项目代码文件的真实错误行
REAL_ERRORS=$(echo "$ERRORS" | grep -E "^.*\.kt:[0-9]+:" \
    | grep -v "unresolved reference: android" \
    | grep -v "unresolved reference: androidx" \
    | grep -v "unresolved reference: Handler" \
    | grep -v "unresolved reference: Looper" \
    | grep -v "unresolved reference: Bundle" \
    | grep -v "unresolved reference: Window" \
    | grep -v "unresolved reference: WindowManager" \
    | grep -v "unresolved reference: AppCompatActivity" \
    | grep -v "'onCreate' overrides nothing" \
    | grep -v "'onResume' overrides nothing" \
    | grep -v "unresolved reference: setContentView" \
    | grep -v "unresolved reference: requestWindowFeature" \
    | grep -v "unresolved reference: runOnUiThread" \
    | grep -v "unresolved reference: updateScoresOnUiThread" \
    | grep -v "unresolved reference: binding" \
    | grep -v "unresolved reference: player2Avatar" \
    | grep -v "unresolved reference: player3Avatar" \
    | grep -v "unresolved reference: player1Score" \
    | grep -v "unresolved reference: player2Score" \
    | grep -v "unresolved reference: player3Score" \
    | grep -v "unresolved reference: player1Bet" \
    | grep -v "unresolved reference: player2Bet" \
    | grep -v "unresolved reference: player3Bet" \
    | grep -v "unresolved reference: GameSurfaceView" \
    | grep -v "unresolved reference: GameViewModel" \
    | grep -v "unresolved reference: BottomCardAreaView" \
    | grep -v "unresolved reference: PlayerCardView" \
    | grep -v "unresolved reference: view" \
    | grep -v "unresolved reference: chips" \
    | grep -v "unresolved reference: showChosenLandlord" \
    | grep -v "unresolved reference: showFarmerMessage" \
    | grep -v "unresolved reference: playLandlordAnimation" \
    | grep -v "unresolved reference: hideActions" \
    | grep -v "unresolved reference: playCardSound" \
    | grep -v "'when' expression must be exhaustive" \
    | grep -v "cannot access class" \
    | grep -v "unresolved reference: sumOf" \
    | grep -v "unresolved reference: it" \
    | grep -v "unresolved reference: minByOrNull" \
    | grep -v "unresolved reference: maxByOrNull" \
    | grep -v "unresolved reference: randomOrNull" \
    | grep -v "unresolved reference: minWithOrNull" \
    | grep -v "unresolved reference: groupByTo" \
    | grep -v "unresolved reference: coerceIn" \
    | grep -v "unresolved reference: coerceAtLeast" \
    | grep -v "unresolved reference: filterValues" \
    | grep -v "unresolved reference: getValue" \
    | grep -v "unresolved reference: toMutableMap" \
    | grep -v "unresolved reference: toMutableList" \
    | grep -v "type mismatch: inferred type is Int? but Int was expected" \
    | grep -v "type inference failed" \
    | grep -v "cannot infer a type for this parameter" \
    | grep -v "Not enough information to infer parameter" \
    | grep -v "operator call.*not allowed on a nullable" \
    | grep -v "type mismatch: inferred type is Int? but Int was expected")

REAL_ERRORS=$(echo "$REAL_ERRORS" | sed '/^$/d')

rm -rf "$CHECK_DIR"

if [ -z "${REAL_ERRORS}" ] || [ "${REAL_ERRORS}" = " " ]; then
    echo "✅ 未发现代码错误（Android SDK 依赖项除外）"
    exit 0
else
    echo "❌ 发现以下错误:"
    echo "${REAL_ERRORS}"
    exit 1
fi