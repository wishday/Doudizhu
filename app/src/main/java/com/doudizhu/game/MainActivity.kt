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
    private var masterThinkSeconds = 5

    private fun loadStrategy(key: String, default: Difficulty): Difficulty {
        val s = strategyPrefs.getString(key, default.name) ?: default.name
        return try { Difficulty.valueOf(s) } catch (_: IllegalArgumentException) { default }
    }

    private fun loadMasterStrategy() {
        farmerTeammateStrategy = loadStrategy("farmer_teammate_strategy", Difficulty.NORMAL)
        playerHintStrategy = loadStrategy("player_hint_strategy", Difficulty.NORMAL)
        masterThinkSeconds = strategyPrefs.getInt("master_think_seconds", 5)
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
        val sec = settings.optInt("masterThinkSeconds", 5).coerceIn(2, 60)
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
                    toast("已导出备份")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    toast("导出失败")
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
                    toast("导入失败：文件无效")
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
        toast("已导入备份")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复历史积分与胜率
        loadStats()
        // 恢复大师模式 AI 策略设置
        loadMasterStrategy()

        // 全屏显示。注意：requestWindowFeature(FEATURE_NO_TITLE) 必须在创建任何
        // AppCompat 子装饰（如 WindowCompat.setDecorFitsSystemWindows 触发的
        // ensureSubDecor）之前调用；一旦提前实例化 delegate 的 sub-decor，
        // requestFeature 会抛 IllegalStateException("Features must be requested
        // before the activity is displayed")，导致 onCreate 打开即闪退。
        // 主题 Theme.DouDiZhu 已是 NoActionBar，此处 try/catch 双重防御。
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        } catch (_: Throwable) {}
        @Suppress("DEPRECATION") // FLAG_FULLSCREEN 在 API 30 起被 WindowInsetsController 取代，
        // 但本项目 minSdk=24，低版本设备仍需此标志实现全屏；高版本的沉浸效果由
        // applyImmersiveFullscreen() 中的新版 WindowInsetsController API 负责。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // 创建游戏视图（放在沉浸设置之前：先完成 setContentView/sub-decor 初始化，
        // 之后的 WindowCompat.setDecorFitsSystemWindows 等调用不会再触发 feature 冲突）
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

        // 沉浸式 + 挖孔屏适配：让内容延伸到刘海/挖孔区域，横屏无黑边。
        // 必须放在 setContentView 之后：WindowCompat.setDecorFitsSystemWindows
        // 会触发 AppCompat ensureSubDecor，若在 requestWindowFeature 之前调用
        // 会导致后续 requestFeature 抛异常（打开即闪退）。
        applyImmersiveFullscreen()
    }

    /**
     * 沉浸式全屏 + 挖孔/刘海屏适配：
     * 1. 隐藏状态栏与导航栏（滑动短暂出现后自动隐藏）；
     * 2. 允许窗口内容延伸到挖孔区域（横屏短边），消除两侧黑边；
     * 3. API 30+ 用 WindowInsetsController 隐藏导航栏并关闭手势条对比度遮罩，
     *    消除全面屏手势白条的白底（旧版 systemUiVisibility 无法控制 gesture handle）。
     */
    private fun applyImmersiveFullscreen() {
        // 整体兜底：沉浸式/挖孔相关 API（LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS、
        // isNavigationBarContrastEnforced 等）在个别厂商 ROM 上可能抛出 NoSuchFieldError /
        // NoSuchMethodError 等 Error（Exception 捕获不到），一旦在 onCreate/onWindowFocusChanged
        // 中抛出会直接导致打开即闪退。此处捕获 Throwable，失败仅影响沉浸效果，不影响启动。
        try {
            applyImmersiveFullscreenInner()
        } catch (_: Throwable) {}
    }

    private fun applyImmersiveFullscreenInner() {
        // 关键：允许内容绘制到刘海/挖孔区域。
        // R(API 30)+ 用 ALWAYS（横竖屏任意侧挖孔都填充）；
        // P(API 28-29) 与 O_MR1(API 27) 只有 shortEdges（横屏左右短边挖孔）；
        // 更早版本无挖孔屏，忽略。
        // 注意：LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS 是 API 30 新增常量，部分厂商 ROM /
        // 旧工具链生成的 APK 在低版本设备上运行时按名字解析该字段会抛 NoSuchFieldError，
        // 导致 onCreate 直接崩溃（打开即闪退）。此处用数值 3 + try/catch 双重防御。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = 3 // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            } catch (_: Throwable) {
                // ALWAYS 设置失败时回退为 shortEdges（API 27+ 稳定可用）
                try {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                } catch (_: Throwable) {}
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
            // isNavigationBarContrastEnforced 是 API 29 新增方法，个别 ROM 上可能缺失
            // （调用抛 NoSuchMethodError），try/catch 防御避免 onCreate 闪退。
            @Suppress("DEPRECATION")
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
            try {
                window.isNavigationBarContrastEnforced = false
            } catch (_: Throwable) {}
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
            // 启动 3 秒手牌逐张滑入动画；动画结束后决定首位叫分玩家，先用与「xx成为地主」
            // 相同的中央提示框展示「xx开始叫地主」，保持 1s 关闭后再正式进入叫分流程。
            gameSurfaceView.startHandReveal {
                val firstBidder = gameEngine.prepareFirstBidder()
                val firstName = gameEngine.players[firstBidder].name
                gameSurfaceView.showCenterPopup("${firstName}开始叫地主", 1000L) {
                    gameEngine.beginBiddingAfterFirstBidderPopup(firstBidder)
                }
            }
        }
    }

    /** 叫分节奏控制（引擎侧实现，UI 仅被动展示快照）：轮到电脑A/B 时**不显示「思考中」**——
     *  引擎经极短反应间隔立即公布该电脑的叫分结果（显示在其牌背下方），**保留展示 1.5s** 后
     *  再进入下一位叫分；所有人叫完 → 中央弹窗「xx成为地主」保持 1.5s
     *  → 关闭后清除所有叫分结果 → 进入正常出牌阶段。 */

    override fun onBiddingStart(playerIndex: Int) {
        runOnUiThread {
            // 发布快照：current 指向即将行动的玩家；因其「已行动」标记为 false 且 UI 不再绘制
            // 「思考中」提示（见 GameSurfaceView.drawAiBidTip），屏幕上只保留各人已公布的叫分结果。
            publishBidDisplay()
        }
    }

    override fun onPlayerBid(playerIndex: Int, score: Int) {
        runOnUiThread {
            // 某玩家叫分完毕：立即刷新展示——该电脑下方显示其叫分结果（叫X分/不叫），
            // 并保持到引擎暂停期结束、下一位行动为止
            publishBidDisplay()
        }
    }

    /** 汇总当前叫分进度并发布到 GameSurfaceView（在两台电脑玩家下方显示各自的叫分情况）。
     *  已行动玩家取 Player.bidScore（引擎写入的有效叫分，0=不叫）；
     *  是否"已行动"由状态机直接判定：叫分顺序为 firstBidderIndex → +1 → +2，
     *  前 bidRoundCount 位即已完成本轮叫分的玩家。 */
    private fun publishBidDisplay() {
        // 叫分展示已改为「引擎状态纯函数」：状态变化只需触发一次重绘，绘制时直接读引擎。
        gameSurfaceView.updateBidDisplay()
    }


    override fun onBiddingComplete(landlordIndex: Int, bottomCards: List<Card>) {
        runOnUiThread {
            // 所有人叫分完成：屏幕中央弹出「xx成为地主」，保持 1.5s；
            // 弹窗关闭回调中清除所有电脑的叫分结果，然后引擎正式进入正常出牌阶段。
            val landlordName = gameEngine.players[landlordIndex].name
            gameSurfaceView.showLandlordPopup(landlordName) {
                // 弹窗已关闭：阶段即将切到 PLAYING，叫分展示由绘制逻辑按阶段自动清除；
                // 直接进入出牌阶段（触发逐帧重绘，清屏立即生效）。
                gameEngine.proceedAfterLandlordPopup()
            }
        }
    }

    override fun onBiddingProceed(landlordIndex: Int) {
        runOnUiThread {
            // 已进入出牌阶段：底牌在顶部中央翻面展示（drawBottomCards），刷新画面即可
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
            // 直接展示本手出牌：setTablePlayedCards 内部会捕获桌上正在展示的上一手牌作为退场动画，
            // 故此处不再预先 clearAllPlayedCards（否则退场动画失效）；结算时不清空，最后一手牌留在桌上
            gameSurfaceView.setTablePlayedCards(playerIndex, cards)
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

    /** 轻量系统提示（替代原游戏内消息提示条：仅用于备份导入/导出等操作反馈） */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
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
