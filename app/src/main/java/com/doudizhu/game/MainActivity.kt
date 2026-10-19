package com.doudizhu.game

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.doudizhu.game.logic.GameEngine
import com.doudizhu.game.logic.GameEngineCallback
import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.ui.GameSurfaceView
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 斗地主游戏主Activity
 * 使用SurfaceView进行Canvas绘制，实现完整的游戏UI
 */
class MainActivity : AppCompatActivity(), GameEngineCallback {

    /** 游戏引擎 */
    private lateinit var gameEngine: GameEngine

    /** 游戏绘制视图 */
    private lateinit var gameSurfaceView: GameSurfaceView

    /** 按模式分别统计：积分 / 局数 / 胜场（普通、大师各自独立） */
    private data class GameStats(var score: Int = 0, var games: Int = 0, var wins: Int = 0)
    private val stats = mutableMapOf(
        Difficulty.NORMAL to GameStats(),
        Difficulty.MASTER to GameStats()
    )

    /** 统计持久化：使用 Activity 级 SharedPreferences，进程重启后仍能恢复 */
    private val statsPrefs by lazy { getPreferences(MODE_PRIVATE) }

    private fun statKey(mode: Difficulty, suffix: String) = "${mode.name.lowercase()}_$suffix"

    private fun loadStats() {
        for (mode in stats.keys) {
            val st = stats[mode]!!
            st.score = statsPrefs.getInt(statKey(mode, "score"), 0)
            st.games = statsPrefs.getInt(statKey(mode, "games"), 0)
            st.wins = statsPrefs.getInt(statKey(mode, "wins"), 0)
        }
    }

    private fun saveStats() {
        statsPrefs.edit().apply {
            for ((mode, st) in stats) {
                putInt(statKey(mode, "score"), st.score)
                putInt(statKey(mode, "games"), st.games)
                putInt(statKey(mode, "wins"), st.wins)
            }
            apply()
        }
    }

    /** 大师模式 AI 策略持久化（独立文件，与普通统计无关） */
    private val strategyPrefs by lazy { getSharedPreferences("master_strategy", MODE_PRIVATE) }
    private var farmerTeammateStrategy = Difficulty.NORMAL
    private var playerHintStrategy = Difficulty.NORMAL
    private var masterThinkSeconds = 3

    private fun loadStrategy(key: String, default: Difficulty): Difficulty {
        val s = strategyPrefs.getString(key, default.name) ?: default.name
        return try { Difficulty.valueOf(s) } catch (_: IllegalArgumentException) { default }
    }

    private fun loadMasterStrategy() {
        farmerTeammateStrategy = loadStrategy("farmer_teammate_strategy", Difficulty.NORMAL)
        playerHintStrategy = loadStrategy("player_hint_strategy", Difficulty.NORMAL)
        masterThinkSeconds = strategyPrefs.getInt("master_think_seconds", 3)
    }

    private fun saveMasterStrategy() {
        strategyPrefs.edit().apply {
            putString("farmer_teammate_strategy", farmerTeammateStrategy.name)
            putString("player_hint_strategy", playerHintStrategy.name)
            putInt("master_think_seconds", masterThinkSeconds)
            apply()
        }
    }

    // ===== 备份与还原（SAF：无需存储权限，文件存于用户自选位置，重装后可导入） =====

    /** 导出：启动系统「创建文档」选择器，由用户选择保存位置与文件名 */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri ?: return@registerForActivityResult; exportBackup(uri) }

    /** 导入：启动系统「打开文档」选择器，由用户选择备份文件（MIME 过滤在 launch 时传入） */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri ?: return@registerForActivityResult; importBackup(uri) }

    private fun launchExportBackup() {
        val time = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
        exportLauncher.launch("doudizhu_backup_$time.json")
    }

    private fun launchImportBackup() {
        importLauncher.launch(arrayOf("application/json", "*/*"))
    }

    /** 组装备份 JSON（设置 + 双模式统计） */
    private fun buildBackupJson(): String {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("exportedAt", SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date()))
        val settings = JSONObject()
        settings.put("farmer_teammate_strategy", farmerTeammateStrategy.name)
        settings.put("player_hint_strategy", playerHintStrategy.name)
        settings.put("masterThinkSeconds", masterThinkSeconds)
        root.put("settings", settings)
        val statsObj = JSONObject()
        for ((mode, st) in stats) {
            val o = JSONObject()
            o.put("score", st.score)
            o.put("games", st.games)
            o.put("wins", st.wins)
            statsObj.put(mode.name, o)
        }
        root.put("stats", statsObj)
        return root.toString(2)
    }

    /** 解析备份 JSON，校验 schema 与取值范围；失败时抛异常 */
    private data class ImportedBackup(
        val farmer: Difficulty, val hint: Difficulty, val sec: Int,
        val stats: Map<Difficulty, GameStats>
    )

    private fun parseBackupJson(text: String): ImportedBackup {
        val root = JSONObject(text)
        if (root.optInt("schema", 1) != 1) throw IllegalArgumentException("不支持的备份格式")
        val settings = root.getJSONObject("settings")
        val farmer = Difficulty.valueOf(settings.optString("farmer_teammate_strategy", Difficulty.NORMAL.name))
        val hint = Difficulty.valueOf(settings.optString("player_hint_strategy", Difficulty.NORMAL.name))
        val sec = settings.optInt("masterThinkSeconds", 3).coerceIn(2, 60)
        val statsObj = root.optJSONObject("stats") ?: JSONObject()
        val imported = mutableMapOf<Difficulty, GameStats>()
        for (mode in listOf(Difficulty.NORMAL, Difficulty.MASTER)) {
            if (statsObj.has(mode.name)) {
                val o = statsObj.getJSONObject(mode.name)
                imported[mode] = GameStats(o.optInt("score", 0), o.optInt("games", 0), o.optInt("wins", 0))
            }
        }
        return ImportedBackup(farmer, hint, sec, imported)
    }

    private fun exportBackup(uri: Uri) {
        thread {
            try {
                val json = buildBackupJson()
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("无法写入文件")
                runOnUiThread {
                    gameSurfaceView.showMessage("已导出备份", 1500)
                    gameSurfaceView.refresh()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    gameSurfaceView.showMessage("导出失败", 1500)
                    gameSurfaceView.refresh()
                }
            }
        }
    }

    /** 待确认的导入数据：解析成功后暂存，用户二次确认后再应用 */
    private var pendingImport: ImportedBackup? = null

    private fun importBackup(uri: Uri) {
        thread {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("无法读取文件")
                val parsed = parseBackupJson(text)
                runOnUiThread {
                    pendingImport = parsed
                    // 先弹二次确认框，确认后才真正覆盖当前设置与统计
                    gameSurfaceView.requestImportConfirm()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    gameSurfaceView.showMessage("导入失败：文件无效", 1500)
                    gameSurfaceView.refresh()
                }
            }
        }
    }

    /** 用户确认导入后应用：覆盖设置与统计，并同步引擎与 UI */
    private fun applyImportedBackup() {
        val data = pendingImport ?: return
        pendingImport = null
        farmerTeammateStrategy = data.farmer
        playerHintStrategy = data.hint
        masterThinkSeconds = data.sec
        for ((m, st) in data.stats) stats[m] = st
        saveMasterStrategy()
        saveStats()
        gameEngine.farmerTeammateStrategy = data.farmer
        gameEngine.playerHintStrategy = data.hint
        gameEngine.masterDeadlineMs = data.sec * 1000L
        gameSurfaceView.setMasterStrategy(data.farmer, data.hint)
        gameSurfaceView.setMasterThinkSeconds(data.sec)
        for ((m, st) in stats) gameSurfaceView.setModeStats(m, st.score, st.games, st.wins)
        gameSurfaceView.showMessage("已导入备份", 1500)
        gameSurfaceView.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复历史积分与胜率
        loadStats()
        // 恢复大师模式 AI 策略设置
        loadMasterStrategy()

        // 全屏显示
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // 沉浸式 + 挖孔屏适配：让内容延伸到刘海/挖孔区域，横屏无黑边
        applyImmersiveFullscreen()

        // 创建游戏视图
        gameSurfaceView = GameSurfaceView(this)

        // 初始化游戏引擎
        gameEngine = GameEngine()
        gameEngine.callback = this
        gameSurfaceView.gameEngine = gameEngine
        for ((mode, st) in stats) {
            gameSurfaceView.setModeStats(mode, st.score, st.games, st.wins)
        }
        gameSurfaceView.setDisplayMode(Difficulty.NORMAL)

        // 主界面设置：重置某模式统计数据（清零并持久化）
        gameSurfaceView.onResetStats = { mode ->
            stats[mode]?.let { it.score = 0; it.games = 0; it.wins = 0 }
            saveStats()
            gameSurfaceView.setModeStats(mode, 0, 0, 0)
            gameSurfaceView.refresh()
        }

        // 恢复大师模式 AI 策略到引擎与 UI 显示
        gameEngine.farmerTeammateStrategy = farmerTeammateStrategy
        gameEngine.playerHintStrategy = playerHintStrategy
        gameEngine.masterDeadlineMs = masterThinkSeconds * 1000L
        gameSurfaceView.setMasterStrategy(farmerTeammateStrategy, playerHintStrategy)
        gameSurfaceView.setMasterThinkSeconds(masterThinkSeconds)

        // 主界面设置：大师模式 AI 策略变更（持久化保存并实时生效）
        gameSurfaceView.onMasterStrategyChange = { farmer, hint ->
            farmerTeammateStrategy = farmer
            playerHintStrategy = hint
            gameEngine.farmerTeammateStrategy = farmer
            gameEngine.playerHintStrategy = hint
            saveMasterStrategy()
            gameSurfaceView.refresh()
        }

        // 主界面设置：大师模式 AI 思考时间（关闭设置时落盘并写入引擎，下次大师出牌即生效）
        gameSurfaceView.onMasterThinkTimeChange = { sec ->
            masterThinkSeconds = sec
            gameEngine.masterDeadlineMs = sec * 1000L
            saveMasterStrategy()
            gameSurfaceView.refresh()
        }

        // 备份与还原：导出/导入（启动 SAF 系统选择器，无需存储权限）
        gameSurfaceView.onExportRequested = { launchExportBackup() }
        gameSurfaceView.onImportRequested = { launchImportBackup() }
        gameSurfaceView.onImportConfirmed = { applyImportedBackup() }

        setContentView(gameSurfaceView)
    }

    /**
     * 沉浸式全屏 + 挖孔/刘海屏适配：
     * 1. 隐藏状态栏与导航栏（滑动短暂出现后自动隐藏）；
     * 2. 允许窗口内容延伸到挖孔区域（横屏短边），消除两侧黑边；
     * 3. API 30+ 用 WindowInsetsController 隐藏导航栏并关闭手势条对比度遮罩，
     *    消除全面屏手势白条的白底（旧版 systemUiVisibility 无法控制 gesture handle）。
     */
    private fun applyImmersiveFullscreen() {
        // 关键：允许内容绘制到刘海/挖孔区域。
        // R(API 30)+ 用 ALWAYS（横竖屏任意侧挖孔都填充）；
        // P(API 28-29) 与 O_MR1(API 27) 只有 shortEdges（横屏左右短边挖孔）；
        // 更早版本无挖孔屏，忽略。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+（Android 11）：新版 WindowInsetsController。
            // 旧版 systemUiVisibility 无法控制手势导航条（gesture handle），
            // 必须用此 API 隐藏导航栏；配合关闭系统白底渐变遮罩，
            // 全面屏手势白条即变为透明（画面直接透出）。
            val controller = window.insetsController
            controller?.hide(WindowInsets.Type.navigationBars())
            controller?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 内容铺满整屏（含状态栏/导航栏区域）
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // 方案 b：关闭导航栏对比度遮罩 + 透明导航栏背景。
            // 原 Manifest 属性 android:enforceNavigationBarContrast="false"
            // 在 minSdk=24 下会导致 AAPT 编译失败（资源定义于 API 29），
            // 故改用代码等效设置；二者均为 API 29+ 方法，本分支（API 30+）安全。
            @Suppress("DEPRECATION")
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
            window.isNavigationBarContrastEnforced = false
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从通知栏下拉、设置页返回等导致焦点恢复时，重新进入沉浸模式
        if (hasFocus) applyImmersiveFullscreen()
    }

    override fun onResume() {
        super.onResume()
        // 如果游戏还没开始，启动新游戏
        if (gameEngine.stateMachine.phase == com.doudizhu.game.state.GamePhase.DEALING
            && gameEngine.players[0].handCards.isEmpty()) {
            gameEngine.startNewGame()
        }
    }

    // ===== GameEngineCallback 实现 =====

    override fun onDealingComplete() {
        runOnUiThread {
            // 启动 3 秒手牌逐张滑入动画，动画结束后进入叫分阶段
            gameSurfaceView.startHandReveal { gameEngine.startBiddingPhase() }
        }
    }

    override fun onBiddingStart(playerIndex: Int) {
        runOnUiThread {
            val name = gameEngine.players[playerIndex].name
            if (playerIndex == 0) {
                gameSurfaceView.showMessage("轮到你叫分", 2000)
            } else {
                gameSurfaceView.showMessage("$name 正在思考...", 1500)
            }
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerBid(playerIndex: Int, score: Int) {
        runOnUiThread {
            val name = gameEngine.players[playerIndex].name
            val text = if (score == 0) "$name 不叫" else "$name 叫 $score 分"
            gameSurfaceView.showMessage(text, 1500)
            gameSurfaceView.refresh()
        }
    }

    override fun onBiddingComplete(landlordIndex: Int, bottomCards: List<Card>) {
        runOnUiThread {
            val name = gameEngine.players[landlordIndex].name
            val cards = bottomCards.joinToString(" ") { it.toString() }
            gameSurfaceView.showMessage("$name 成为地主！底牌: $cards", 3000)
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerTurn(playerIndex: Int) {
        runOnUiThread {
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerPlay(playerIndex: Int, cards: List<Card>, group: CardGroup) {
        runOnUiThread {
            gameSurfaceView.clearAllPlayedCards()
            gameSurfaceView.setTablePlayedCards(playerIndex, cards)
            val name = gameEngine.players[playerIndex].name
            val cardText = cards.joinToString(" ") { it.toString() }
            gameSurfaceView.showMessage("$name: $cardText", 2000)
            // 炸弹/火箭用爆炸轰鸣音效，普通牌型用甩牌声
            gameSurfaceView.playCardSound(
                isBomb = group.type == CardType.BOMB || group.type == CardType.ROCKET)

            val isLastCard = gameEngine.players[playerIndex].handCards.size == 1
            val isBig = group.type == CardType.BOMB || group.type == CardType.ROCKET || cards.size >= 8
            // 大出牌：炸弹 / 火箭 / 一次出牌≥8张，加 1.5 秒长振动（与最后一张提醒互斥，避免振动互相覆盖）
            if (isBig && !isLastCard) {
                gameSurfaceView.playBigVibration()
            }
            // 任意玩家剩最后一张牌：特殊提醒音效 + 三段脉冲振动
            if (isLastCard) {
                gameSurfaceView.playLastCardAlert()
                gameSurfaceView.playLastCardVibration()
            }
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerPass(playerIndex: Int) {
        runOnUiThread {
            // 在该玩家头像区展示「不出」气泡动画（不自动消退，由他人出牌/清桌时移除），
            // 原独立消息提示框不再重复显示
            gameSurfaceView.registerPassBubble(playerIndex)
            gameSurfaceView.refresh()
        }
    }

    override fun onGameOver(winnerIndex: Int, isLandlordWin: Boolean) {
        runOnUiThread {
            val isHumanLandlord = gameEngine.stateMachine.landlordIndex == 0
            val isHumanWin = isHumanLandlord == isLandlordWin

            // 计算本局得分
            val baseScore = gameEngine.stateMachine.currentBidScore
            val bombCount = gameEngine.stateMachine.bombCount
            // 倍数 = 2^bombCount（用位运算，避免 Math.pow 浮点误差与 Int 溢出）
            var multiplier = 1L shl bombCount
            // 春天：地主获胜且农民全程未出过牌；反春天：农民获胜且地主只出过首手
            val isSpring = if (isLandlordWin) {
                gameEngine.stateMachine.farmerPlayCount == 0
            } else {
                gameEngine.stateMachine.landlordPlayCount == 1
            }
            if (isSpring) multiplier *= 2

            // 农民视角单份分数 = 底分 × 倍数
            val unitScore = baseScore * multiplier
            // 标准斗地主中地主输赢为农民的 2 倍；本项目以人类视角单人记账，故人类是地主时翻倍
            val humanGain = if (isHumanLandlord) unitScore * 2 else unitScore
            val springTag = if (isSpring) "（春天×2）" else ""

            // 按本局 AI 难度归入对应模式的统计
            val mode = gameEngine.aiDifficulty
            val st = stats[mode]!!

            if (isHumanWin) {
                st.score += humanGain.toInt()
                gameSurfaceView.playWinSound()
            } else {
                st.score -= humanGain.toInt()
                gameSurfaceView.playLoseSound()
            }
            // 结算横幅：记录胜负与本局积分信息（原独立消息提示框内容并入横幅展示），触发滑入动画
            val multiplierText = "×$multiplier$springTag"
            gameSurfaceView.showSettlement(isHumanWin,
                scoreDelta = if (isHumanWin) humanGain.toInt() else -humanGain.toInt(),
                multiplierText = multiplierText)

            // 统计该模式的游戏局数与胜场
            st.games++
            if (isHumanWin) st.wins++
            gameSurfaceView.setModeStats(mode, st.score, st.games, st.wins)
            gameSurfaceView.setDisplayMode(mode)
            gameSurfaceView.refresh()
            saveStats()
        }
    }

    override fun onPause() {
        super.onPause()
        // 兜底保存：应用退到后台时持久化积分与胜率，避免异常退出丢数据
        saveStats()
        saveMasterStrategy()
    }

    override fun onRequestRefresh() {
        runOnUiThread {
            gameSurfaceView.refresh()
        }
    }
}
