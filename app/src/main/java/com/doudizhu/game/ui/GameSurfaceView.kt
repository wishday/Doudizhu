package com.doudizhu.game.ui

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.doudizhu.game.logic.GameEngine
import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.state.GamePhase
import kotlin.math.cos
import kotlin.math.abs

/**
 * 游戏主绘制视图（横屏模式）
 * 布局：左右两侧AI，底部玩家手牌，中央桌面展示所有出牌
 * 支持：滑动选牌、当前回合高亮、计分显示、音效反馈、按钮按压反馈
 */
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** 游戏引擎引用 */
    lateinit var gameEngine: GameEngine

    /** 绘制线程 */
    private var drawThread: DrawThread? = null

    /** 屏幕尺寸 */
    private var screenWidth = 0
    private var screenHeight = 0

    // ====== 横屏适配尺寸（大幅放大） ======
    /** 手牌宽度 */
    private var cardW = 160f
    /** 手牌高度 */
    private var cardH = 230f
    /** 手牌圆角 */
    private var cardRadius = 16f
    /** 手牌间距（动态计算，最多填满底部95%） */
    private var handSpacing = 80f
    /** 桌面展示牌宽度（放大2倍） */
    private var tableCardW = 140f
    /** 桌面展示牌高度（放大2倍） */
    private var tableCardH = 196f
    /** 桌面展示牌间距 */
    private var tableSpacing = 72f
    /** AI牌背宽度（放大2倍） */
    private var aiBackW = 70f
    /** AI牌背高度（放大2倍） */
    private var aiBackH = 98f

    /** 根据屏幕尺寸动态计算所有UI尺寸 */
    private fun recalcSizes() {
        val scale = (screenWidth.toFloat() / 1920f).coerceIn(0.85f, 1.5f)
        cardW = (160f * scale).coerceAtLeast(120f)
        cardH = (230f * scale).coerceAtLeast(170f)
        cardRadius = (16f * scale).coerceAtLeast(12f)
        // 动态计算手牌间距，最多填满底部95%宽度
        val hand = if (::gameEngine.isInitialized) gameEngine.players[0].handCards else emptyList()
        if (hand.size > 1) {
            val availableWidth = screenWidth * 0.95f
            handSpacing = ((availableWidth - cardW) / (hand.size - 1)).coerceIn(cardW * 0.35f, cardW * 0.90f)
        } else {
            handSpacing = cardW * 0.5f
        }
        tableCardW = (140f * scale).coerceAtLeast(100f)
        tableCardH = (196f * scale).coerceAtLeast(140f)
        tableSpacing = (72f * scale).coerceAtLeast(52f)
        aiBackW = (70f * scale).coerceAtLeast(50f)
        aiBackH = (98f * scale).coerceAtLeast(70f)
    }

    // ====== 画笔 ======
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val cardPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val cardBorderPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 4f
        color = Color.parseColor("#424242")
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val smallTextPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT
    }
    private val buttonPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val buttonTextPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
    }
    private val shadowPaint = Paint().apply {
        isAntiAlias = true; color = Color.parseColor("#33000000"); style = Paint.Style.FILL
    }
    /** 高亮画笔（当前回合玩家） */
    private val highlightPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 8f
        color = Color.parseColor("#FFEB3B")
    }
    /** 高亮发光效果 */
    private val glowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Color.parseColor("#30FFEB3B")
    }

    // ====== 缓存的装饰画笔（避免每帧分配 Paint 对象） ======
    private val ovalPaint = Paint().apply {
        color = Color.parseColor("#15491A"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val topBarPaint = Paint().apply { color = Color.parseColor("#80000000"); style = Paint.Style.FILL }
    private val aiInfoPaint = Paint().apply { color = Color.parseColor("#60000000"); style = Paint.Style.FILL }
    private val buttonOverlayPaint = Paint().apply {
        color = Color.parseColor("#30000000"); style = Paint.Style.FILL
    }
    private val buttonHighlightPaint = Paint().apply {
        color = Color.parseColor("#20FFFFFF"); style = Paint.Style.FILL
    }
    /** 主界面难度按钮：模式名（大/加粗，白色）；字号整体放大2号 */
    private val modeNamePaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE; textSize = 66f
    }
    /** 主界面难度按钮：统计信息（小/不加粗，浅灰）；字号整体放大2号 */
    private val modeStatPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT
        color = Color.parseColor("#E0E0E0"); textSize = 34f
    }
    private val msgBgPaint = Paint().apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
    private val errBgPaint = Paint().apply { color = Color.parseColor("#DD000000"); style = Paint.Style.FILL }
    private val aiHighlightPaint = Paint().apply {
        color = Color.argb(120, 255, 152, 0); style = Paint.Style.FILL
    }
    private val modalDimPaint = Paint().apply { color = Color.argb(170, 0, 0, 0); style = Paint.Style.FILL }
    private val panelPaint = Paint().apply { color = Color.parseColor("#263238"); style = Paint.Style.FILL }
    private val panelBorderPaint = Paint().apply {
        color = Color.parseColor("#546E7A"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val gearPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.parseColor("#B0BEC5") }
    private val gearHolePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.parseColor("#0D3B0D") }
    /** 背景渐变缓存（仅尺寸变化时重建） */
    private var bgGradient: LinearGradient? = null
    private var bgGradientW = 0f
    private var bgGradientH = 0f

    /** 按钮区域（绘制线程每帧整体重建后原子发布，触摸线程始终读到完整列表） */
    data class ButtonRect(val text: String, val rect: RectF, val color: Int, val pressedColor: Int, val action: String)
    @Volatile
    private var currentButtons: List<ButtonRect> = emptyList()
    /** 当前按下的按钮动作（按下时记录，释放时执行，避免按钮列表重建导致索引错位） */
    private var pressedAction: String? = null

    /** 所有玩家的桌面出牌展示（0=人类, 1=右AI, 2=左AI） */
    private val tablePlayedCards = arrayOfNulls<List<Card>>(3)

    /** 消息提示 */
    private var messageText = ""
    private var messageTimer = 0L

    /** 错误提示（红色） */
    private var errorText = ""
    private var errorTimer = 0L

    /** 滑动选牌状态 */
    private var isDragging = false
    /** 按下时的起始坐标（用于区分点击/滑动、防误触） */
    private var downX = 0f
    private var downY = 0f
    /** 点击与滑动的位移阈值（防误触：小于该距离视为点击） */
    private val tapSlop = 24f
    /** 本次拖拽中最近处理过的牌索引（防止手指停留同一张牌时反复切换） */
    private var lastDragCardIndex = -1

    /** 按模式分别统计：积分 / 局数 / 胜场（普通、大师各自独立） */
    private data class ModeStat(var score: Int = 0, var games: Int = 0, var wins: Int = 0)
    private val modeStats = mutableMapOf(
        Difficulty.NORMAL to ModeStat(),
        Difficulty.MASTER to ModeStat()
    )

    /** 当前展示统计所用的难度（可点击右上角普通/大师切换查看） */
    private var displayMode: Difficulty = Difficulty.NORMAL

    /** 主界面设置窗口与重置确认状态 */
    private var settingsOpen = false
    private var confirmResetMode: Difficulty? = null
    /** 重置某模式统计的回调（由 MainActivity 注入，负责清零并持久化保存） */
    var onResetStats: ((Difficulty) -> Unit)? = null

    /** 大师模式 AI 策略（本地显示状态，由 MainActivity 启动时初始化） */
    private var masterFarmerStrategy: Difficulty = Difficulty.NORMAL
    private var masterHintStrategy: Difficulty = Difficulty.NORMAL
    /** 大师模式 AI 最大思考时间（秒），本地显示状态，2~60，默认 3；仅在关闭设置时落盘 */
    private var masterThinkSeconds: Int = 3
    /** 大师模式 AI 策略变更回调（由 MainActivity 注入，负责持久化保存） */
    var onMasterStrategyChange: ((Difficulty, Difficulty) -> Unit)? = null
    /** 大师模式 AI 思考时间变更回调（关闭设置时由 MainActivity 注入，负责落盘并写入引擎） */
    var onMasterThinkTimeChange: ((Int) -> Unit)? = null
    /** 备份与还原：导出/导入回调（由 MainActivity 注入，负责启动 SAF 系统选择器） */
    var onExportRequested: (() -> Unit)? = null
    var onImportRequested: (() -> Unit)? = null
    /** 导入二次确认：解析成功后置位，弹窗等待用户确认；确认后由 onImportConfirmed 应用 */
    var importConfirmPending = false
    var onImportConfirmed: (() -> Unit)? = null
    fun requestImportConfirm() { importConfirmPending = true; refresh() }

    /** 当前回合高亮动画帧 */
    private var highlightFrame = 0

    // ====== 开局手牌展示动画（3 秒内逐张滑入） ======
    /** 动画起始时间戳（System.currentTimeMillis） */
    @Volatile
    private var dealRevealStart = 0L
    /** 是否正在播放手牌展示动画 */
    @Volatile
    private var isHandReveal = false
    /** 动画结束回调（由 MainActivity 注入，通常为进入叫分阶段） */
    @Volatile
    private var onRevealComplete: (() -> Unit)? = null
    /** 发牌动画代次：每次开局自增，防止上一局延迟的结束回调串入下一局 */
    @Volatile
    private var revealGeneration = 0
    /** 逐张错峰间隔（ms） */
    private val REVEAL_STAGGER_MS = 140L
    /** 单张入场时长（ms） */
    private val REVEAL_DUR_MS = 700L
    /** 动画总时长：最后一张出发时刻 + 单张时长 */
    private fun revealTotalMs(handSize: Int) = (handSize - 1) * REVEAL_STAGGER_MS + REVEAL_DUR_MS

    /** 音效播放器 */
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        initAudio()
    }

    /** 初始化音效/振动（surface 重建后也会重新调用） */
    private fun initAudio() {
        try {
            if (toneGenerator == null) {
                // 音量：ToneGenerator 音量参数上限为 100（即系统流音量的满档），已是可程序控制的最大值
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            }
            // Android 12+ (API 31) 优先使用 VibratorManager，获取失败则回退旧 API
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {}
    }

    /** 播放简单音效 */
    private fun playTone(toneType: Int, durationMs: Int = 100) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (_: Exception) {}
    }

    /** 振动类型（按反馈强度区分，便于映射到系统预定义波形） */
    private enum class VibrationKind { TICK, CLICK, HEAVY }

    /**
     * 播放振动反馈（针对线性马达 LRA 优化）：
     * 用显式长脉冲 + 中等振幅，确保 LRA 质量块起振且可清晰感知（振幅已按手感减半）。
     */
    private fun vibrate(kind: VibrationKind) {
        try {
            val v = vibrator ?: return
            val (dur, amp) = when (kind) {
                VibrationKind.TICK -> 50L to 25
                VibrationKind.CLICK -> 90L to 32
                VibrationKind.HEAVY -> 150L to 32
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(dur, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(dur)
            }
        } catch (_: Exception) {}
    }

    /** 大出牌（炸弹/火箭/一次出≥8张）的长振动反馈：0.8 秒（振幅已减半） */
    fun playBigVibration() {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(800L, 64))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(800L)
            }
        } catch (_: Exception) {}
    }

    /** 剩最后一张牌的特殊提醒音效 */
    fun playLastCardAlert() {
        playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260)
    }

    /** 剩最后一张牌的三段脉冲振动提醒（震-停-震-停-震，振幅减半） */
    fun playLastCardVibration() {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 120, 80, 120, 80, 120)
                val amps = intArrayOf(0, 64, 0, 64, 0, 64)
                v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 120, 80, 120, 80, 120), -1)
            }
        } catch (_: Exception) {}
    }

    /** 按钮轻触反馈（按下） */
    private fun hapticButtonPress() {
        vibrate(VibrationKind.CLICK)
    }

    /** 按钮释放反馈（弱于按下，体现按键回弹） */
    private fun hapticButtonRelease() {
        vibrate(VibrationKind.TICK)
    }

    /** 按钮动作确认反馈（叫分/出牌/不出/提示成功时的明确振动） */
    private fun hapticActionConfirm() {
        vibrate(VibrationKind.HEAVY)
    }

    /** 播放出牌音效 */
    fun playCardSound() {
        playTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    /** 播放错误音效 */
    fun playErrorSound() {
        playTone(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150)
        vibrate(VibrationKind.HEAVY)
    }

    /** 播放胜利音效 */
    fun playWinSound() {
        playTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenWidth = width
        screenHeight = height
        recalcSizes()
        initAudio()
        refresh()           // 重新进入应用时强制至少重绘一帧，避免主界面/结算等静态界面黑屏
        startDrawThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        recalcSizes()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopDrawThread()
        toneGenerator?.release()
        toneGenerator = null
    }

    fun startDrawThread() {
        drawThread = DrawThread(holder, this).also { it.start() }
    }

    fun stopDrawThread() {
        drawThread?.running = false
        drawThread = null
    }

    /** 待重绘请求计数（AtomicInteger：避免绘制期间发生的 refresh 被 onFrameDrawn 丢失） */
    private val redrawRequests = java.util.concurrent.atomic.AtomicInteger(1)

    /** 请求重绘：状态变化时由引擎回调/触摸事件调用 */
    fun refresh() {
        redrawRequests.incrementAndGet()
    }

    /** 绘制线程是否需要绘制（有变化，或存在动画/消息需要持续刷新） */
    fun shouldDraw(): Boolean {
        if (redrawRequests.get() > 0) return true
        if (!::gameEngine.isInitialized) return false
        if (isHandReveal) return true
        if (gameEngine.stateMachine.phase == GamePhase.PLAYING) return true
        return messageText.isNotEmpty() || errorText.isNotEmpty()
    }

    /** 本帧已绘制完成（仅当恰好有一个待处理请求时清空，避免丢失绘制期间新到达的 refresh） */
    fun onFrameDrawn() {
        redrawRequests.compareAndSet(1, 0)
    }

    /** 启动开局手牌展示动画（逐张滑入，约 3 秒），结束后回调 onComplete */
    fun startHandReveal(onComplete: () -> Unit) {
        revealGeneration++
        dealRevealStart = System.currentTimeMillis()
        onRevealComplete = onComplete
        isHandReveal = true
        refresh()
    }

    /** 动画结束：停止动画并（仅一次、且代次匹配时）触发回调，防止串场 */
    private fun finishReveal() {
        isHandReveal = false
        val cb = onRevealComplete
        val gen = revealGeneration
        onRevealComplete = null
        cb?.let { runnable -> post { if (gen == revealGeneration) runnable() } }
    }

    fun showMessage(msg: String, durationMs: Long = 2000) {
        messageText = msg
        messageTimer = System.currentTimeMillis() + durationMs
    }

    fun showError(msg: String, durationMs: Long = 2000) {
        errorText = msg
        errorTimer = System.currentTimeMillis() + durationMs
        playErrorSound()
    }

    /** 设置某模式的统计（积分/局数/胜场） */
    fun setModeStats(diff: Difficulty, score: Int, games: Int, wins: Int) {
        modeStats[diff]?.let {
            it.score = score
            it.games = games
            it.wins = wins
        }
    }

    /** 设置右上角统计面板当前展示的难度（普通/大师，可点击切换） */
    fun setDisplayMode(diff: Difficulty) {
        displayMode = diff
    }

    /** 初始化大师模式 AI 策略的显示状态（由 MainActivity 启动时调用） */
    fun setMasterStrategy(farmerTeammate: Difficulty, playerHint: Difficulty) {
        masterFarmerStrategy = farmerTeammate
        masterHintStrategy = playerHint
    }

    /** 初始化大师模式 AI 思考时间显示（秒），由 MainActivity 启动时调用 */
    fun setMasterThinkSeconds(seconds: Int) {
        masterThinkSeconds = seconds.coerceIn(2, 60)
    }

    /** 设置任意玩家的桌面出牌展示 */
    fun setTablePlayedCards(playerIndex: Int, cards: List<Card>?) {
        tablePlayedCards[playerIndex] = cards
    }

    /** 清除所有桌面出牌展示 */
    fun clearAllPlayedCards() {
        for (i in tablePlayedCards.indices) tablePlayedCards[i] = null
    }

    // ==================== 触摸事件（支持滑动选牌 + 按钮按压反馈） ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastDragCardIndex = -1
                // 检查按钮点击（记录动作而非索引，防止绘制线程重建按钮导致错位）
                for (btn in currentButtons) {
                    if (btn.rect.contains(x, y)) {
                        pressedAction = btn.action
                        // “提示”按钮不振动
                        if (btn.action != "hint") hapticButtonPress()
                        refresh()
                        return true
                    }
                }
                pressedAction = null
                // 记录起点，先不切换选中，等 UP 时判断是点击还是滑动
                if (gameEngine.stateMachine.phase == GamePhase.PLAYING
                    && gameEngine.stateMachine.currentPlayerIndex == 0) {
                    isDragging = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 滑动选牌：位移超过阈值后切换选中/取消（滑到已选牌即取消）
                if (isDragging && gameEngine.stateMachine.phase == GamePhase.PLAYING
                    && gameEngine.stateMachine.currentPlayerIndex == 0
                    && movedBeyondSlop(x, y)) {
                    toggleCardFromTouch(x, y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 处理按钮释放：仅当抬起时触点仍在按钮范围内才生效，滑出范围视为取消
                val action = pressedAction
                if (action != null) {
                    val stillOnButton = currentButtons
                        .firstOrNull { it.action == action }?.rect?.contains(x, y) == true
                    if (stillOnButton) {
                        handleButtonAction(action)
                        // “提示”按钮不振动
                        if (action != "hint") hapticButtonRelease()
                    }
                    // 滑出范围：仅取消按下态，不触发任何动作（玩家可滑开远离按钮来取消）
                    pressedAction = null
                    refresh()
                    return true
                }
                // 处理选牌：未滑动视为点击，切换选中状态（可取消选牌）
                if (isDragging) {
                    if (!movedBeyondSlop(x, y)) {
                        toggleCardAt(x, y)
                    }
                    isDragging = false
                    lastDragCardIndex = -1
                    refresh()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                lastDragCardIndex = -1
                pressedAction = null
                refresh()
                return true
            }
        }
        return true
    }

    /** 判断移动距离是否超过点击阈值 */
    private fun movedBeyondSlop(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return (dx * dx + dy * dy) > tapSlop * tapSlop
    }

    /** 点击：切换某张牌的选中状态（支持取消选牌） */
    private fun toggleCardAt(touchX: Float, touchY: Float) {
        val idx = hitTestCard(touchX, touchY)
        if (idx < 0) return
        if (idx in gameEngine.selectedCardIndices) {
            gameEngine.selectedCardIndices.remove(idx)
            playTone(ToneGenerator.TONE_PROP_ACK, 25)
        } else {
            gameEngine.selectedCardIndices.add(idx)
            playTone(ToneGenerator.TONE_PROP_ACK, 30)
        }
        refresh()
    }

    /** 滑动：切换经过的牌的选中状态（滑到已选牌则取消） */
    private fun toggleCardFromTouch(touchX: Float, touchY: Float) {
        val idx = hitTestCard(touchX, touchY)
        if (idx < 0) return
        if (idx == lastDragCardIndex) return
        lastDragCardIndex = idx
        if (idx in gameEngine.selectedCardIndices) {
            gameEngine.selectedCardIndices.remove(idx)
            playTone(ToneGenerator.TONE_PROP_ACK, 25)
        } else {
            gameEngine.selectedCardIndices.add(idx)
            playTone(ToneGenerator.TONE_PROP_ACK, 30)
        }
        refresh()
    }

    /**
     * 命中测试：根据相邻牌中点分界 + 垂直带判定点中的牌
     * 解决手牌重叠时难以精确选中的问题（左侧露出部分也能点到）
     */
    private fun hitTestCard(touchX: Float, touchY: Float): Int {
        val hand = gameEngine.players[0].handCards
        if (hand.isEmpty()) return -1

        val n = hand.size
        val totalWidth = handSpacing * (n - 1) + cardW
        val startX = (screenWidth - totalWidth) / 2
        val baseY = screenHeight - cardH - 40f

        // 垂直判定带（含选中上移 60f 和微弧偏移的容差）
        val minY = baseY - 70f - 24f
        val maxY = baseY + cardH + 24f
        if (touchY < minY || touchY > maxY) return -1

        // 用相邻牌中点作为左右分界，命中最近的牌
        for (i in 0 until n) {
            val cx = startX + i * handSpacing
            var leftBound = Float.NEGATIVE_INFINITY
            var rightBound = Float.POSITIVE_INFINITY
            if (i > 0) leftBound = (startX + (i - 1) * handSpacing + cx) / 2f
            if (i < n - 1) rightBound = (cx + startX + (i + 1) * handSpacing) / 2f
            if (touchX >= leftBound && touchX <= rightBound) return i
        }
        return -1
    }

    private fun handleButtonAction(action: String) {
        when {
            action == "play" -> {
                val selectedCards = gameEngine.selectedCardIndices
                    .sorted()
                    .map { gameEngine.players[0].handCards[it] }
                if (selectedCards.isNotEmpty()) {
                    // 先校验牌型合法性，不合法则提示且不清空桌面
                    val group = com.doudizhu.game.logic.CardRuleEngine.identify(selectedCards)
                    if (group.type == CardType.INVALID) {
                        showError("牌型不合法，请重新选择", 2000)
                        return
                    }
                    // 检查是否能管上家（用规则引擎的正规合法校验，避免炸弹/火箭误判）
                    val lastGroup = gameEngine.stateMachine.lastPlayedGroup
                    val lastPlayer = gameEngine.stateMachine.lastPlayedPlayerIndex
                    if (lastGroup != null && lastPlayer != 0) {
                        if (!com.doudizhu.game.logic.CardRuleEngine.isValidPlay(group, lastGroup)) {
                            showError("打不过上家的牌，请重新选择", 2000)
                            return
                        }
                    }
                    // 合法出牌，清空桌面并出牌
                    clearAllPlayedCards()
                    playCardSound()
                    hapticActionConfirm()
                    gameEngine.humanPlay(selectedCards)
                } else {
                    showError("请先选择要出的牌", 1500)
                }
            }
            action == "pass" -> {
                hapticActionConfirm()
                gameEngine.humanPass()
            }
            action == "hint" -> {
                val hint = gameEngine.getHint()
                if (hint != null) {
                    gameEngine.selectedCardIndices.clear()
                    val hand = gameEngine.players[0].handCards
                    hint.forEach { card ->
                        val idx = hand.indexOfFirst { it.id == card.id }
                        if (idx >= 0) gameEngine.selectedCardIndices.add(idx)
                    }
                    playTone(ToneGenerator.TONE_PROP_ACK, 50)
                } else {
                    showError("没有能出的牌", 1500)
                }
                refresh()
            }
            action.startsWith("bid_") -> {
                hapticActionConfirm()
                gameEngine.humanBid(action.removePrefix("bid_").toInt())
                playTone(ToneGenerator.TONE_PROP_ACK, 50)
            }
            action == "restart" -> {
                clearAllPlayedCards()
                hapticActionConfirm()
                gameEngine.startNewGame()
            }
            action == "start_normal" -> startGameAt(Difficulty.NORMAL)
            action == "start_master" -> startGameAt(Difficulty.MASTER)
            action == "to_menu" -> {
                clearAllPlayedCards()
                hapticActionConfirm()
                gameEngine.returnToMenu()
            }
            action == "open_settings" -> {
                settingsOpen = true
                refresh()
            }
            action == "close_settings" -> {
                // 关闭前持久化保存设置项（大师策略实时落盘；思考时间与之一并落盘）
                onMasterStrategyChange?.invoke(masterFarmerStrategy, masterHintStrategy)
                onMasterThinkTimeChange?.invoke(masterThinkSeconds)
                settingsOpen = false
                confirmResetMode = null
                refresh()
            }
            action == "reset_normal" -> {
                confirmResetMode = Difficulty.NORMAL
                refresh()
            }
            action == "reset_master" -> {
                confirmResetMode = Difficulty.MASTER
                refresh()
            }
            action == "confirm_reset" -> {
                confirmResetMode?.let { onResetStats?.invoke(it) }
                settingsOpen = false
                confirmResetMode = null
                refresh()
            }
            action == "cancel_reset" -> {
                confirmResetMode = null
                refresh()
            }
            action == "export_backup" -> {
                onExportRequested?.invoke()
            }
            action == "import_backup" -> {
                onImportRequested?.invoke()
            }
            action == "confirm_import" -> {
                importConfirmPending = false
                onImportConfirmed?.invoke()
            }
            action == "cancel_import" -> {
                importConfirmPending = false
                refresh()
            }
            action == "master_farmer_normal" -> {
                masterFarmerStrategy = Difficulty.NORMAL
                onMasterStrategyChange?.invoke(masterFarmerStrategy, masterHintStrategy)
                refresh()
            }
            action == "master_farmer_master" -> {
                masterFarmerStrategy = Difficulty.MASTER
                onMasterStrategyChange?.invoke(masterFarmerStrategy, masterHintStrategy)
                refresh()
            }
            action == "master_hint_normal" -> {
                masterHintStrategy = Difficulty.NORMAL
                onMasterStrategyChange?.invoke(masterFarmerStrategy, masterHintStrategy)
                refresh()
            }
            action == "master_hint_master" -> {
                masterHintStrategy = Difficulty.MASTER
                onMasterStrategyChange?.invoke(masterFarmerStrategy, masterHintStrategy)
                refresh()
            }
            action == "master_think_dec" -> {
                // 步进器：仅调整本地显示，不立即落盘（关闭设置时统一保存）
                masterThinkSeconds = (masterThinkSeconds - 1).coerceAtLeast(2)
                refresh()
            }
            action == "master_think_inc" -> {
                masterThinkSeconds = (masterThinkSeconds + 1).coerceAtMost(60)
                refresh()
            }
        }
    }

    /** 主界面难度按钮：设置 AI 难度并直接进入对应难度对局 */
    private fun startGameAt(diff: Difficulty) {
        gameEngine.aiDifficulty = diff
        displayMode = diff
        hapticActionConfirm()
        clearAllPlayedCards()
        gameEngine.startNewGame()
    }

    // ==================== 主绘制 ====================

    fun drawGame(canvas: Canvas) {
        if (screenWidth == 0 || screenHeight == 0) return
        highlightFrame = (highlightFrame + 1) % 60
        val pendingButtons = mutableListOf<ButtonRect>()

        // 每帧重新计算手牌间距
        recalcSizes()

        drawBackground(canvas)
        drawTopBar(canvas)
        drawBottomCards(canvas)

        // 横屏布局：AI在左右两侧（主界面不显示电脑A/电脑B信息框）
        if (gameEngine.stateMachine.phase != GamePhase.MENU) {
            drawAIPlayer(canvas, 1, screenWidth - 260f, 100f)   // 右侧AI
            drawAIPlayer(canvas, 2, 260f, 100f)                  // 左侧AI
        }

        // 桌面中央展示所有玩家出的牌
        drawTablePlayedCards(canvas)

        // 电脑思考中提示（画在出牌区之后，避免被中央牌面遮挡）
        if (gameEngine.stateMachine.phase != GamePhase.MENU) {
            drawAiTurnTip(canvas, 1, screenWidth - 260f, 100f)
            drawAiTurnTip(canvas, 2, 260f, 100f)
        }

        // 底部人类手牌
        drawHumanHand(canvas)

        // 当前回合高亮提示
        drawTurnHighlight(canvas)

        // 按钮
        drawButtons(canvas, pendingButtons)

        // 轮到你出牌提示（出牌按钮下方、手牌上方）
        drawHumanTurnTip(canvas)

        // 消息
        drawMessage(canvas)

        // 错误提示
        drawError(canvas)

        // 计分显示
        drawStats(canvas)

        // 结算时显示所有玩家剩余牌（正面）
        if (gameEngine.stateMachine.phase == GamePhase.GAME_OVER || 
            gameEngine.stateMachine.phase == GamePhase.SETTLING) {
            drawAllRemainingCards(canvas)
        }

        // 全部绘制完成后原子发布按钮列表，触摸线程永远不会读到半成品
        currentButtons = pendingButtons
    }

    private fun drawBackground(canvas: Canvas) {
        if (bgGradient == null || bgGradientW != screenWidth.toFloat() || bgGradientH != screenHeight.toFloat()) {
            bgGradient = LinearGradient(
                0f, 0f, 0f, screenHeight.toFloat(),
                Color.parseColor("#0D3B0D"), Color.parseColor("#1B5E20"),
                Shader.TileMode.CLAMP
            )
            bgGradientW = screenWidth.toFloat()
            bgGradientH = screenHeight.toFloat()
        }
        bgPaint.shader = bgGradient
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), bgPaint)
        bgPaint.shader = null

        // 桌面中央椭圆装饰
        val cx = screenWidth / 2f
        val cy = screenHeight * 0.40f
        canvas.drawOval(
            RectF(cx - screenWidth * 0.30f, cy - screenHeight * 0.20f,
                  cx + screenWidth * 0.30f, cy + screenHeight * 0.20f),
            ovalPaint
        )
    }

    private fun drawTopBar(canvas: Canvas) {
        val barH = 80f
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), barH, topBarPaint)
        // 回合指示已下放到各玩家牌堆附近绘制（AI 见 drawAiTurnTip，人类见 drawHumanTurnTip），
        // 顶部状态栏仅保留背景条，右上角统计面板由 drawStats 绘制
    }

    /** 绘制底牌（顶部中央，放大2.5倍） */
    private fun drawBottomCards(canvas: Canvas) {
        if (gameEngine.stateMachine.bottomCards.isEmpty()) return
        val miniW = 100f
        val miniH = 140f
        val gap = 20f
        val totalW = miniW * 3 + gap * 2
        val startX = (screenWidth - totalW) / 2
        val y = 88f

        for ((i, card) in gameEngine.stateMachine.bottomCards.withIndex()) {
            drawMiniCard(canvas, startX + i * (miniW + gap), y + 20f, miniW, miniH,
                card, faceUp = gameEngine.stateMachine.hasLandlord)
        }
    }

    /** 横屏AI玩家区域（左右两侧，文字放大2.5倍，牌放大2倍） */
    private fun drawAIPlayer(canvas: Canvas, playerIndex: Int, centerX: Float, topY: Float) {
        val player = gameEngine.players[playerIndex]
        val isCurrentTurn = gameEngine.stateMachine.currentPlayerIndex == playerIndex
            && gameEngine.stateMachine.phase == GamePhase.PLAYING

        // 信息面板（放大）
        val panelW = 320f
        val panelH = 180f
        val panelX = centerX - panelW / 2

        // 当前回合高亮背景
        if (isCurrentTurn) {
            val pulseAlpha = ((abs(highlightFrame - 30) / 30.0f) * 100 + 60).toInt()
            aiHighlightPaint.color = Color.argb(pulseAlpha, 255, 235, 59)
            canvas.drawRoundRect(RectF(panelX - 10f, topY - 10f, panelX + panelW + 10f, topY + panelH + 10f),
                22f, 22f, aiHighlightPaint)
        }

        canvas.drawRoundRect(RectF(panelX, topY, panelX + panelW, topY + panelH), 18f, 18f, aiInfoPaint)

        // 角色（身份：地主/农民）移到顶部，字号用原名称的 50f，颜色保持不变
        val roleText = if (player.role == com.doudizhu.game.model.PlayerRole.LANDLORD) "地主" else "农民"
        textPaint.textSize = 50f
        textPaint.color = if (player.role == com.doudizhu.game.model.PlayerRole.LANDLORD)
            Color.parseColor("#FFD600") else Color.parseColor("#A5D6A7")
        canvas.drawText(roleText, centerX, topY + 55f, textPaint)

        // 剩余张数（放大2.5倍: 30*2.5=75 -> 用50f）
        textPaint.textSize = 46f
        textPaint.color = Color.parseColor("#FFD600")
        canvas.drawText("${player.cardCount}张", centerX, topY + 110f, textPaint)

        // 名称（电脑A/电脑B）移到底部，字号用原角色的 38f，颜色改为灰白色
        textPaint.textSize = 38f
        textPaint.color = Color.parseColor("#D9D9D9")
        canvas.drawText(player.name, centerX, topY + 160f, textPaint)

        // 牌背扇形（放大2倍）：结算/结束时由正面剩余牌占据该位置，不再画牌背
        if (gameEngine.stateMachine.phase != GamePhase.SETTLING &&
            gameEngine.stateMachine.phase != GamePhase.GAME_OVER) {
            val count = minOf(player.cardCount, 12)
            val backSpacing = aiBackW * 0.55f
            val totalBackW = backSpacing * (count - 1) + aiBackW
            val backStartX = centerX - totalBackW / 2
            for (i in 0 until count) {
                drawCardBack(canvas, backStartX + i * backSpacing, topY + panelH + 16f, aiBackW, aiBackH)
            }
        }
    }

    /**
     * 电脑「思考中」提示：绘制在该电脑玩家牌堆下方。
     * 单独成函数并在桌面出牌之后绘制，避免被中央出牌区遮挡。
     * 坐标与 drawAIPlayer 的牌背区保持一致（panelH = 180f）。
     */
    private fun drawAiTurnTip(canvas: Canvas, playerIndex: Int, centerX: Float, topY: Float) {
        if (gameEngine.stateMachine.phase != GamePhase.PLAYING) return
        if (gameEngine.stateMachine.currentPlayerIndex != playerIndex) return
        textPaint.textSize = 44f
        textPaint.color = Color.parseColor("#FFD600")
        textPaint.textAlign = Paint.Align.CENTER
        val tip = if (playerIndex == 1) "电脑A思考中..." else "电脑B思考中..."
        canvas.drawText(tip, centerX, topY + 180f + 16f + aiBackH + 52f, textPaint)
    }

    /**
     * 「轮到你出牌」提示：绘制在出牌按钮下方、玩家手牌上方。
     * 按钮顶部 y = screenHeight - cardH - 380f，高 130f，故按钮底部 = screenHeight - cardH - 250f；
     * 手牌高亮框顶部 = screenHeight - cardH - 110f，取两者之间的空隙居中。
     */
    private fun drawHumanTurnTip(canvas: Canvas) {
        if (gameEngine.stateMachine.phase != GamePhase.PLAYING) return
        if (gameEngine.stateMachine.currentPlayerIndex != 0) return
        textPaint.textSize = 44f
        textPaint.color = Color.parseColor("#FFD600")
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("轮到你出牌", screenWidth / 2f, screenHeight - cardH - 165f, textPaint)
    }

    /** 桌面中央展示所有玩家的出牌（放大） */
    private fun drawTablePlayedCards(canvas: Canvas) {
        val centerY = screenHeight * 0.38f

        // 左侧AI出的牌
        tablePlayedCards[2]?.let { cards ->
            if (cards.isNotEmpty()) {
                drawPlayedCardsAt(canvas, cards, screenWidth * 0.30f, centerY)
                textPaint.textSize = 36f
                textPaint.color = Color.parseColor("#A5D6A7")
                canvas.drawText("电脑B", screenWidth * 0.30f, centerY - tableCardH / 2 - 20f, textPaint)
            }
        }

        // 人类玩家出的牌
        tablePlayedCards[0]?.let { cards ->
            if (cards.isNotEmpty()) {
                drawPlayedCardsAt(canvas, cards, screenWidth * 0.50f, centerY + 50f)
                textPaint.textSize = 36f
                textPaint.color = Color.parseColor("#FFFFFF")
                canvas.drawText("你", screenWidth * 0.50f, centerY + 50f - tableCardH / 2 - 20f, textPaint)
            }
        }

        // 右侧AI出的牌
        tablePlayedCards[1]?.let { cards ->
            if (cards.isNotEmpty()) {
                drawPlayedCardsAt(canvas, cards, screenWidth * 0.70f, centerY)
                textPaint.textSize = 36f
                textPaint.color = Color.parseColor("#A5D6A7")
                canvas.drawText("电脑A", screenWidth * 0.70f, centerY - tableCardH / 2 - 20f, textPaint)
            }
        }
    }

    /** 在指定位置绘制一组出牌 */
    private fun drawPlayedCardsAt(canvas: Canvas, cards: List<Card>, centerX: Float, centerY: Float) {
        if (cards.isEmpty()) return
        val totalW = tableSpacing * (cards.size - 1) + tableCardW
        val startX = centerX - totalW / 2
        val startY = centerY - tableCardH / 2

        for ((i, card) in cards.withIndex()) {
            drawMiniCard(canvas, startX + i * tableSpacing, startY, tableCardW, tableCardH, card, faceUp = true)
        }
    }

    /** 底部人类手牌（间距最多填满95%宽度，开局有 3 秒逐张滑入动画） */
    private fun drawHumanHand(canvas: Canvas) {
        val hand = gameEngine.players[0].handCards
        if (hand.isEmpty()) return

        val totalWidth = handSpacing * (hand.size - 1) + cardW
        val startX = (screenWidth - totalWidth) / 2
        val baseY = screenHeight - cardH - 40f

        val centerIdx = (hand.size - 1) / 2.0
        val now = System.currentTimeMillis()
        val elapsed = if (isHandReveal) now - dealRevealStart else Long.MAX_VALUE

        for ((i, card) in hand.withIndex()) {
            val isSelected = i in gameEngine.selectedCardIndices
            val cx = startX + i * handSpacing

            // 微弧效果
            val distFromCenter = i - centerIdx
            val arcOffset = (-cos(distFromCenter * 0.04) * 6.0 + 6.0).toFloat()
            val rotation = (distFromCenter * 0.8).toFloat()

            // 默认位置（含选中上移、微弧偏移）
            var cy = baseY - (if (isSelected) 60f else 0f) - arcOffset
            var alpha = 1f
            var scale = 1f

            if (isHandReveal) {
                val p = ((elapsed - i * REVEAL_STAGGER_MS).toFloat() / REVEAL_DUR_MS).coerceIn(0f, 1f)
                if (p <= 0f) continue   // 尚未轮到该牌入场，留空
                val e = easeOutBack(p)
                cy += (1f - e) * cardH * 0.85f   // 从下方滑入
                scale = 0.92f + 0.08f * e        // 轻微放大归位（带回弹）
                alpha = (p * 1.4f).coerceIn(0.12f, 1f)
            }

            drawRevealingCard(canvas, cx, cy, card, isSelected, rotation, scale, alpha)
        }

        if (isHandReveal && elapsed >= revealTotalMs(hand.size)) {
            finishReveal()
        }
    }

    /** 带缩放与淡入的卡牌绘制（用于开局滑入动画，无离屏图层，性能友好） */
    private fun drawRevealingCard(canvas: Canvas, x: Float, y: Float, card: Card,
                                  selected: Boolean, rotation: Float, scale: Float, alpha: Float) {
        // 完全就位（无缩放、无淡入）：直接原样绘制，零额外开销
        if (alpha >= 0.999f && scale == 1f) {
            drawCard(canvas, x, y, card, selected, rotation)
            return
        }
        canvas.save()
        if (scale != 1f) {
            canvas.scale(scale, scale, x + cardW / 2, y + cardH / 2)
        }
        drawCard(canvas, x, y, card, selected, rotation, alpha)
        canvas.restore()
    }

    /** ease-out-back：落定带轻微回弹（过冲后归位），比纯线性更自然 */
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t - 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }

    /** 绘制单张扑克牌（手牌，大尺寸）；alpha<1 时用于开局淡入，绘制后还原共享画笔的 alpha */
    private fun drawCard(canvas: Canvas, x: Float, y: Float, card: Card,
                         selected: Boolean, rotation: Float = 0f, alpha: Float = 1f) {
        val fade = alpha < 0.999f
        val a = if (fade) (alpha * 255f).toInt().coerceIn(0, 255) else 255
        canvas.save()
        canvas.rotate(rotation, x + cardW / 2, y + cardH / 2)

        // 选中阴影
        if (selected) {
            if (fade) shadowPaint.alpha = a
            canvas.drawRoundRect(x + 4f, y + 8f, x + cardW + 4f, y + cardH + 8f,
                cardRadius, cardRadius, shadowPaint)
            if (fade) shadowPaint.alpha = 255
        }

        // 牌面
        cardPaint.color = if (selected) Color.parseColor("#FFF9C4") else Color.WHITE
        if (fade) cardPaint.alpha = a
        canvas.drawRoundRect(x, y, x + cardW, y + cardH, cardRadius, cardRadius, cardPaint)
        if (fade) cardPaint.alpha = 255

        // 边框
        cardBorderPaint.color = if (selected) Color.parseColor("#FFD600") else Color.parseColor("#BDBDBD")
        cardBorderPaint.strokeWidth = if (selected) 6f else 3f
        if (fade) cardBorderPaint.alpha = a
        canvas.drawRoundRect(x, y, x + cardW, y + cardH, cardRadius, cardRadius, cardBorderPaint)
        if (fade) cardBorderPaint.alpha = 255

        val textColor = if (card.isRed) Color.parseColor("#D32F2F") else Color.parseColor("#212121")

        // 左上角花色+点数
        smallTextPaint.textSize = cardW * 0.15f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.LEFT
        if (fade) smallTextPaint.alpha = a
        canvas.drawText(card.suitSymbol, x + 14f, y + cardH * 0.18f, smallTextPaint)
        smallTextPaint.textSize = cardW * 0.36f
        canvas.drawText(card.displayText, x + 14f, y + cardH * 0.36f, smallTextPaint)
        if (fade) smallTextPaint.alpha = 255

        // 中央大字
        textPaint.textSize = cardW * 0.62f
        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.CENTER
        if (fade) textPaint.alpha = a
        canvas.drawText(card.displayText, x + cardW / 2, y + cardH * 0.62f, textPaint)
        if (fade) textPaint.alpha = 255

        // 右下角花色
        smallTextPaint.textSize = cardW * 0.15f
        smallTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(card.suitSymbol, x + cardW - 14f, y + cardH - 24f, smallTextPaint)
        smallTextPaint.textAlign = Paint.Align.LEFT

        canvas.restore()
    }

    /** 绘制小尺寸牌（桌面展示/底牌） */
    private fun drawMiniCard(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                             card: Card, faceUp: Boolean) {
        if (!faceUp) { drawCardBack(canvas, x, y, w, h); return }

        val r = 10f
        cardPaint.color = Color.WHITE
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardPaint)
        cardBorderPaint.color = Color.parseColor("#BDBDBD")
        cardBorderPaint.strokeWidth = 2f
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardBorderPaint)

        val textColor = if (card.isRed) Color.parseColor("#D32F2F") else Color.parseColor("#212121")

        smallTextPaint.textSize = w * 0.32f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(card.suitSymbol, x + 6f, y + h * 0.22f, smallTextPaint)
        smallTextPaint.textSize = w * 0.40f
        canvas.drawText(card.displayText, x + 6f, y + h * 0.46f, smallTextPaint)

        textPaint.textSize = w * 0.58f
        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(card.displayText, x + w / 2, y + h * 0.78f, textPaint)
    }

    /** 绘制牌背 */
    private fun drawCardBack(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = 8f
        cardPaint.color = Color.parseColor("#1565C0")
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardPaint)
        cardBorderPaint.color = Color.parseColor("#0D47A1")
        cardBorderPaint.strokeWidth = 2f
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardBorderPaint)
        // 内部花纹
        cardPaint.color = Color.parseColor("#1976D2")
        val m = w * 0.12f
        canvas.drawRoundRect(x + m, y + m, x + w - m, y + h - m, r, r, cardPaint)
    }

    // ==================== 结算时显示所有玩家剩余牌（正面） ====================

    private fun drawAllRemainingCards(canvas: Canvas) {
        // 结算/结束时，把两个电脑的剩余牌显示到它们原本显示手牌的位置（左/右上角）
        val backY = 100f + 180f + 16f   // 与 drawAIPlayer 中牌背位置一致：topY + panelH + 16f
        val miniW = 90f
        val miniH = 126f
        val miniGap = 12f
        val margin = 24f
        val centerGap = 100f   // 中线处两条牌带之间留一个“底牌大小”的缝隙
        val bandW = screenWidth / 2f    // 左右各占半屏，避免两侧重叠

        // 左侧AI（电脑B）剩余牌：贴左边缘起排，向右延伸到中线缝隙左缘为止
        drawRemainingRow(
            canvas, gameEngine.players[2].handCards,
            bandLeft = margin, bandRight = bandW - centerGap / 2f,
            backY, miniW, miniH, miniGap, "电脑B剩余", leftAlign = true
        )
        // 右侧AI（电脑A）剩余牌：贴右边缘排布，向左延伸到中线缝隙右缘为止
        drawRemainingRow(
            canvas, gameEngine.players[1].handCards,
            bandLeft = bandW + centerGap / 2f, bandRight = screenWidth - margin,
            backY, miniW, miniH, miniGap, "电脑A剩余", leftAlign = false
        )
    }

    /**
     * 在 [bandLeft, bandRight] 区间内绘制一行剩余牌。
     * 牌数较少时按正常间距排布；排不下时压缩步距（重叠）保证整行都在区间内，不会被屏幕裁切。
     */
    private fun drawRemainingRow(
        canvas: Canvas,
        cards: List<Card>,
        bandLeft: Float,
        bandRight: Float,
        backY: Float,
        miniW: Float,
        miniH: Float,
        miniGap: Float,
        label: String,
        leftAlign: Boolean
    ) {
        if (cards.isEmpty()) return
        val n = cards.size
        val availableW = bandRight - bandLeft
        val naturalW = (miniW + miniGap) * (n - 1) + miniW
        // 放得下就用正常间距；放不下就按比例压缩步距（最小保留 0.3*miniW，避免完全叠死）
        val pitch = if (naturalW <= availableW) {
            miniW + miniGap
        } else {
            ((availableW - miniW) / (n - 1)).coerceAtLeast(miniW * 0.3f)
        }
        // 行宽 = (n-1) 个步距 + 最后一张牌宽；左对齐从 bandLeft 起，右对齐顶到 bandRight
        val rowW = (n - 1) * pitch + miniW
        val startX = if (leftAlign) bandLeft else bandRight - rowW

        textPaint.textSize = 28f
        textPaint.color = Color.parseColor("#A5D6A7")
        textPaint.textAlign = if (leftAlign) Paint.Align.LEFT else Paint.Align.RIGHT
        canvas.drawText(label, if (leftAlign) bandLeft else bandRight, backY - 10f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER   // 还原默认对齐，避免影响其它绘制

        for ((i, card) in cards.withIndex()) {
            drawMiniCard(canvas, startX + i * pitch, backY, miniW, miniH, card, faceUp = true)
        }
    }

    // ==================== 当前回合高亮提示 ====================

    private fun drawTurnHighlight(canvas: Canvas) {
        if (gameEngine.stateMachine.phase != GamePhase.PLAYING) return
        val current = gameEngine.stateMachine.currentPlayerIndex

        when (current) {
            0 -> {
                // 底部玩家高亮框
                val hand = gameEngine.players[0].handCards
                if (hand.isEmpty()) return
                val totalWidth = handSpacing * (hand.size - 1) + cardW
                val startX = (screenWidth - totalWidth) / 2
                val baseY = screenHeight - cardH - 40f
                val pulseAlpha = ((abs(highlightFrame - 30) / 30.0f) * 150 + 80).toInt()

                // 发光边框
                highlightPaint.color = Color.argb(pulseAlpha, 255, 235, 59)
                highlightPaint.strokeWidth = 8f
                canvas.drawRoundRect(
                    RectF(startX - 14f, baseY - 70f, startX + totalWidth + 14f, baseY + cardH + 14f),
                    22f, 22f, highlightPaint
                )
            }
            1 -> {
                // 右侧AI高亮
                val pulseAlpha = ((abs(highlightFrame - 30) / 30.0f) * 120 + 60).toInt()
                highlightPaint.color = Color.argb(pulseAlpha, 255, 152, 0)
                highlightPaint.strokeWidth = 6f
                canvas.drawRoundRect(
                    RectF(screenWidth - 430f, 80f, screenWidth - 90f, 310f),
                    20f, 20f, highlightPaint
                )
            }
            2 -> {
                // 左侧AI高亮
                val pulseAlpha = ((abs(highlightFrame - 30) / 30.0f) * 120 + 60).toInt()
                highlightPaint.color = Color.argb(pulseAlpha, 255, 152, 0)
                highlightPaint.strokeWidth = 6f
                canvas.drawRoundRect(
                    RectF(90f, 80f, 430f, 310f),
                    20f, 20f, highlightPaint
                )
            }
        }
    }

    // ==================== 按钮（放大2倍 + 按压变色反馈 + 位置上移） ====================

    private fun drawButtons(canvas: Canvas, target: MutableList<ButtonRect>) {
        val phase = gameEngine.stateMachine.phase
        val currentPlayer = gameEngine.stateMachine.currentPlayerIndex

        // 按钮尺寸（放大2倍）
        var btnW = 340f
        val btnH = 130f
        val gap = 48f

        when {
            // 主界面：难度选择（按钮大小为出牌按钮2倍，居中排列，点击直接进入对应难度）
            phase == GamePhase.MENU -> {
            if (confirmResetMode != null) {
                // 二次确认弹窗（叠加在设置窗口之上；设置窗口仅画面板不注册按钮，避免重叠误触）
                drawSettingsWindow(canvas, target, showButtons = false)
                drawConfirmDialog(canvas, target, confirmResetMode!!)
            } else if (importConfirmPending) {
                // 导入二次确认弹窗（叠加在设置窗口之上；设置窗口仅画面板不注册按钮）
                drawSettingsWindow(canvas, target, showButtons = false)
                drawImportConfirmDialog(canvas, target)
            } else if (settingsOpen) {
                    drawSettingsWindow(canvas, target, showButtons = true)
                } else {
                    // 难度按钮宽度缩小为原来一半（原 2 倍出牌按钮宽 -> 1 倍），上方居中显示「请选择难度」
                    val dW = (btnW * 2f + 40f) / 2f
                    val dH = btnH * 2f + 24f
                    val dGap = gap * 2f
                    val normalColor = Color.parseColor("#388E3C")
                    val normalPressed = Color.parseColor("#1B5E20")
                    val masterColor = Color.parseColor("#D32F2F")
                    val masterPressed = Color.parseColor("#B71C1C")
                    val horizTotal = dW * 2f + dGap
                    val blockTop = if (horizTotal <= screenWidth) {
                        screenHeight / 2f - dH / 2f
                    } else {
                        (screenHeight - (dH * 2f + dGap)) / 2f
                    }
                    // 标题字号与「普通/大师」一致（复用 modeNamePaint：加粗白字、居中）
                    canvas.drawText("请选择难度", screenWidth / 2f, blockTop - 60f, modeNamePaint)
                    if (horizTotal <= screenWidth) {
                        // 横屏：两个按钮左右居中
                        val startX = (screenWidth - horizTotal) / 2f
                        val y = screenHeight / 2f - dH / 2f
                        addModeButton(canvas, target, Difficulty.NORMAL, startX, y, dW, dH, normalColor, normalPressed, "start_normal")
                        addModeButton(canvas, target, Difficulty.MASTER, startX + dW + dGap, y, dW, dH, masterColor, masterPressed, "start_master")
                    } else {
                        // 屏幕过窄：改为上下居中竖排
                        val x = (screenWidth - dW) / 2f
                        val totalH = dH * 2f + dGap
                        val startY = (screenHeight - totalH) / 2f
                        addModeButton(canvas, target, Difficulty.NORMAL, x, startY, dW, dH, normalColor, normalPressed, "start_normal")
                        addModeButton(canvas, target, Difficulty.MASTER, x, startY + dH + dGap, dW, dH, masterColor, masterPressed, "start_master")
                    }
                    // 右下角设置图标
                    addSettingsButton(canvas, target)
                }
            }
            // 叫地主阶段
            phase == GamePhase.BIDDING && currentPlayer == 0 -> {
                // 只显示允许的分数（必须高于当前最高分，否则不叫）
                val maxBid = gameEngine.stateMachine.currentMaxBid
                val allowedScores = listOf(0, 1, 2, 3).filter { it == 0 || it > maxBid }
                // 按钮数量多时按屏幕宽度缩小，避免溢出屏幕
                val bidCount = allowedScores.size
                val rawTotal = btnW * bidCount + gap * (bidCount - 1)
                if (rawTotal > screenWidth) {
                    btnW = ((screenWidth - gap * (bidCount - 1)) / bidCount).coerceAtLeast(120f)
                }
                val totalW = btnW * bidCount + gap * (bidCount - 1)
                val startX = (screenWidth - totalW) / 2
                val y = screenHeight - cardH - 380f  // 位置上移

                val labels = mapOf(0 to "不叫", 1 to "1分", 2 to "2分", 3 to "3分")
                val colors = mapOf(
                    0 to Color.parseColor("#616161"), 1 to Color.parseColor("#1976D2"),
                    2 to Color.parseColor("#F57C00"), 3 to Color.parseColor("#D32F2F")
                )
                val pressedColors = mapOf(
                    0 to Color.parseColor("#424242"), 1 to Color.parseColor("#0D47A1"),
                    2 to Color.parseColor("#E65100"), 3 to Color.parseColor("#B71C1C")
                )
                allowedScores.forEachIndexed { i, s ->
                    addButton(canvas, target, labels[s]!!, startX + i * (btnW + gap), y, btnW, btnH,
                        colors[s]!!, pressedColors[s]!!, "bid_$s")
                }
            }
            // 出牌阶段
            phase == GamePhase.PLAYING && currentPlayer == 0 -> {
                val canPass = !gameEngine.stateMachine.mustPlay()
                val y = screenHeight - cardH - 380f  // 位置上移

                if (canPass) {
                    val totalW = btnW * 3 + gap * 2
                    val startX = (screenWidth - totalW) / 2
                    addButton(canvas, target, "不出", startX, y, btnW, btnH, Color.parseColor("#616161"),
                        Color.parseColor("#424242"), "pass")
                    addButton(canvas, target, "提示", startX + btnW + gap, y, btnW, btnH, Color.parseColor("#1976D2"),
                        Color.parseColor("#0D47A1"), "hint")
                    addButton(canvas, target, "出牌", startX + (btnW + gap) * 2, y, btnW, btnH, Color.parseColor("#388E3C"),
                        Color.parseColor("#1B5E20"), "play")
                } else {
                    val totalW = btnW * 2 + gap
                    val startX = (screenWidth - totalW) / 2
                    addButton(canvas, target, "提示", startX, y, btnW, btnH, Color.parseColor("#1976D2"),
                        Color.parseColor("#0D47A1"), "hint")
                    addButton(canvas, target, "出牌", startX + btnW + gap, y, btnW, btnH, Color.parseColor("#388E3C"),
                        Color.parseColor("#1B5E20"), "play")
                }
            }
            // 游戏结束
            phase == GamePhase.GAME_OVER || phase == GamePhase.SETTLING -> {
                val bigW = 400f
                val bigH = 120f
                addButton(canvas, target, "再来一局", (screenWidth - bigW) / 2, screenHeight / 2 + 90f,
                    bigW, bigH, Color.parseColor("#388E3C"), Color.parseColor("#1B5E20"), "restart")
                addButton(canvas, target, "返回主界面", (screenWidth - bigW) / 2, screenHeight / 2 + 270f,
                    bigW, bigH, Color.parseColor("#1976D2"), Color.parseColor("#0D47A1"), "to_menu")
            }
        }
    }

    private fun addButton(canvas: Canvas, target: MutableList<ButtonRect>, text: String, x: Float, y: Float,
                          w: Float, h: Float, color: Int, pressedColor: Int, action: String) {
        val rect = RectF(x, y, x + w, y + h)
        target.add(ButtonRect(text, rect, color, pressedColor, action))

        // 判断是否被按下（按动作匹配，按钮列表重建也不会错位）
        val isPressed = action == pressedAction
        buttonPaint.color = if (isPressed) pressedColor else color
        canvas.drawRoundRect(rect, 22f, 22f, buttonPaint)
        
        // 按下时添加深色覆盖层
        if (isPressed) {
            canvas.drawRoundRect(rect, 22f, 22f, buttonOverlayPaint)
        }
        
        // 按钮高光
        canvas.drawRoundRect(RectF(x, y, x + w, y + h * 0.5f), 22f, 22f, buttonHighlightPaint)

        // 文字颜色固定白色：buttonTextPaint 被设置窗口等复用且会被改成灰色，
        // 若此处不显式复位，退出设置后游戏内按钮会继承灰色（叫分/提示/出牌变灰）。
        buttonTextPaint.color = Color.WHITE
        buttonTextPaint.textSize = 56f
        canvas.drawText(text, x + w / 2, y + h / 2 + 18f, buttonTextPaint)
    }

    /**
     * 主界面难度按钮（多行）：第 1 行模式名(大/加粗)，第 2、3 行统计(胜率/积分，不加粗)。
     * 点击区域与动作与普通按钮一致；统计与右上角同源 [modeStats]。
     */
    private fun addModeButton(
        canvas: Canvas, target: MutableList<ButtonRect>, mode: Difficulty,
        x: Float, y: Float, w: Float, h: Float, color: Int, pressedColor: Int, action: String
    ) {
        val rect = RectF(x, y, x + w, y + h)
        target.add(ButtonRect(mode.name, rect, color, pressedColor, action))

        val isPressed = action == pressedAction
        buttonPaint.color = if (isPressed) pressedColor else color
        canvas.drawRoundRect(rect, 22f, 22f, buttonPaint)
        if (isPressed) canvas.drawRoundRect(rect, 22f, 22f, buttonOverlayPaint)
        canvas.drawRoundRect(RectF(x, y, x + w, y + h * 0.5f), 22f, 22f, buttonHighlightPaint)

        val stat = modeStats[mode] ?: ModeStat()
        val rate = if (stat.games > 0) (stat.wins * 100.0 / stat.games) else 0.0
        val rateLine = "胜率:${stat.wins}/${stat.games} (${"%.0f".format(rate)}%)"
        val scoreLine = "积分:${stat.score}"

        val nameH = 64f
        val statH = 32f
        val lineGap = 14f
        val totalH = nameH + statH * 2 + lineGap * 2
        val blockTop = y + (h - totalH) / 2f
        val c0 = blockTop + nameH / 2f
        val c1 = blockTop + nameH + lineGap + statH / 2f
        val c2 = blockTop + nameH + lineGap + statH + lineGap + statH / 2f
        canvas.drawText(
            if (mode == Difficulty.MASTER) "大师" else "普通",
            x + w / 2f, c0 + nameH * 0.32f, modeNamePaint
        )
        canvas.drawText(rateLine, x + w / 2f, c1 + statH * 0.32f, modeStatPaint)
        canvas.drawText(scoreLine, x + w / 2f, c2 + statH * 0.32f, modeStatPaint)
    }

    /** 主界面右下角设置图标（齿轮），位置向左上偏移便于点击 */
    private fun addSettingsButton(canvas: Canvas, target: MutableList<ButtonRect>) {
        val size = 96f
        val margin = 36f
        val offset = 60f   // 向「左上」移动：x 左移、y 上移
        val x = screenWidth - margin - size - offset
        val y = screenHeight - margin - size - offset
        val rect = RectF(x, y, x + size, y + size)
        val cx = x + size / 2f
        val cy = y + size / 2f
        val isPressed = pressedAction == "open_settings"
        drawGearIcon(
            canvas, cx, cy, size * 0.46f,
            if (isPressed) Color.parseColor("#FFFFFF") else Color.parseColor("#B0BEC5")
        )
        target.add(ButtonRect("设置", rect, 0, 0, "open_settings"))
    }

    /** 绘制齿轮图标（中心 cx,cy，半径 r） */
    private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        gearPaint.color = color
        gearHolePaint.color = Color.parseColor("#0D3B0D")
        canvas.save()
        canvas.translate(cx, cy)
        val teeth = 8
        val toothW = r * 0.30f
        for (i in 0 until teeth) {
            canvas.save()
            canvas.rotate(360f / teeth * i)
            canvas.drawRoundRect(RectF(-toothW / 2, -r * 0.95f, toothW / 2, -r * 0.62f), 3f, 3f, gearPaint)
            canvas.restore()
        }
        canvas.drawCircle(0f, 0f, r * 0.62f, gearPaint)
        canvas.drawCircle(0f, 0f, r * 0.30f, gearHolePaint)
        canvas.restore()
    }

    /** 设置窗口（三个面板：大师模式设置 / 重置胜率数据 / 备份与还原，并排且居中对称；
     * 关闭按钮独立置于三框下方居中）；showButtons=false 时只画面板不注册按钮（供确认弹窗叠加） */
    private fun drawSettingsWindow(canvas: Canvas, target: MutableList<ButtonRect>, showButtons: Boolean) {
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), modalDimPaint)

        val panelH = 700f
        val gap = 40f
        // 三面板：宽度按屏宽均分（含两侧边距），并限制上限 520，保证整体居中且对称
        val panelW = minOf((screenWidth - gap * 2f - 40f * 2f) / 3f, 520f)
        val totalW = panelW * 3f + gap * 2f
        val startX = (screenWidth - totalW) / 2f
        val py = (screenHeight - panelH) / 2f

        // 左面板：大师模式设置
        drawMasterStrategyPanel(canvas, target, showButtons, startX, py, panelW, panelH)
        // 中面板：数据设置（重置胜率数据）
        drawDataPanel(canvas, target, showButtons, startX + (panelW + gap), py, panelW, panelH)
        // 右面板：备份与还原（导出/导入）
        drawBackupPanel(canvas, target, showButtons, startX + 2f * (panelW + gap), py, panelW, panelH)

        // 关闭按钮独立出来，置于三个框下方居中
        if (showButtons) {
            val cw = 320f
            val ch = 96f
            val cx = (screenWidth - cw) / 2f
            val cy = py + panelH + 40f
            addButton(canvas, target, "关闭", cx, cy, cw, ch,
                Color.parseColor("#616161"), Color.parseColor("#424242"), "close_settings")
        }
    }

    /** 右面板：数据设置（标题「重置胜率数据」+ 两个简化重置按钮：普通模式 / 大师模式） */
    private fun drawDataPanel(canvas: Canvas, target: MutableList<ButtonRect>, showButtons: Boolean,
                               px: Float, py: Float, pw: Float, ph: Float) {
        val panel = RectF(px, py, px + pw, py + ph)
        canvas.drawRoundRect(panel, 28f, 28f, panelPaint)
        canvas.drawRoundRect(panel, 28f, 28f, panelBorderPaint)

        buttonTextPaint.textSize = 48f
        buttonTextPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText("重置胜率数据", px + pw / 2f, py + 64f, buttonTextPaint)

        if (!showButtons) return

        val bw = pw - 80f
        val bh = 110f
        val bx = px + 40f
        var by = py + 150f
        addButton(canvas, target, "普通模式", bx, by, bw, bh,
            Color.parseColor("#388E3C"), Color.parseColor("#1B5E20"), "reset_normal")
        by += bh + 40f
        addButton(canvas, target, "大师模式", bx, by, bw, bh,
            Color.parseColor("#D32F2F"), Color.parseColor("#B71C1C"), "reset_master")
    }

    /** 右面板：备份与还原（导出/导入备份，使用 SAF 系统选择器，无需存储权限） */
    private fun drawBackupPanel(canvas: Canvas, target: MutableList<ButtonRect>, showButtons: Boolean,
                                px: Float, py: Float, pw: Float, ph: Float) {
        val panel = RectF(px, py, px + pw, py + ph)
        canvas.drawRoundRect(panel, 28f, 28f, panelPaint)
        canvas.drawRoundRect(panel, 28f, 28f, panelBorderPaint)

        buttonTextPaint.textSize = 48f
        buttonTextPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText("备份与还原", px + pw / 2f, py + 64f, buttonTextPaint)

        if (!showButtons) return

        val bw = pw - 80f
        val bh = 110f
        val bx = px + 40f
        var by = py + 150f
        addButton(canvas, target, "导出备份", bx, by, bw, bh,
            Color.parseColor("#388E3C"), Color.parseColor("#1B5E20"), "export_backup")
        by += bh + 40f
        addButton(canvas, target, "导入备份", bx, by, bw, bh,
            Color.parseColor("#1976D2"), Color.parseColor("#0D47A1"), "import_backup")
    }

    /** 左面板：大师模式设置（两个策略开关，普通/大师二选一；去掉“策略”二字，底部仅保留一行说明） */
    private fun drawMasterStrategyPanel(canvas: Canvas, target: MutableList<ButtonRect>, showButtons: Boolean,
                                         px: Float, py: Float, pw: Float, ph: Float) {
        val panel = RectF(px, py, px + pw, py + ph)
        canvas.drawRoundRect(panel, 28f, 28f, panelPaint)
        canvas.drawRoundRect(panel, 28f, 28f, panelBorderPaint)

        buttonTextPaint.textSize = 48f
        buttonTextPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText("大师模式设置", px + pw / 2f, py + 64f, buttonTextPaint)

        if (!showButtons) return

        val bw = (pw - 80f - 24f) / 2f
        val bh = 80f
        val bx = px + 40f
        val optGap = 24f

        // 第一行：农民队友ai
        buttonTextPaint.textSize = 34f
        buttonTextPaint.color = Color.parseColor("#E0E0E0")
        canvas.drawText("农民队友ai", px + pw / 2f, py + 150f, buttonTextPaint)
        drawStrategyToggle(canvas, target, bx, py + 170f, bw, bh, optGap,
            "普通", "大师", masterFarmerStrategy, "master_farmer_normal", "master_farmer_master")

        // 第二行：玩家提示ai
        buttonTextPaint.textSize = 34f
        buttonTextPaint.color = Color.parseColor("#E0E0E0")
        canvas.drawText("玩家提示ai", px + pw / 2f, py + 320f, buttonTextPaint)
        drawStrategyToggle(canvas, target, bx, py + 340f, bw, bh, optGap,
            "普通", "大师", masterHintStrategy, "master_hint_normal", "master_hint_master")

        // 第三行：ai最大思考时间(秒)，步进器 − [数值] +（2~60，默认3，关闭时落盘）
        // 数值显示在中间单元格，确保“X 秒”位于 − 与 + 之间
        buttonTextPaint.textSize = 34f
        buttonTextPaint.color = Color.parseColor("#E0E0E0")
        canvas.drawText("ai最大思考时间(秒)", px + pw / 2f, py + 500f, buttonTextPaint)

        val stepGap = 120f                       // 中间数值单元格宽度，容纳“X 秒”文本
        val sbw = (pw - 80f - stepGap) / 2f      // 两端 −/+ 按钮宽度（与面板内边距一致）
        val sbx = px + 40f
        val sby = py + 520f
        addButton(canvas, target, "−", sbx, sby, sbw, 80f,
            Color.parseColor("#546E7A"), Color.parseColor("#37474F"), "master_think_dec")
        addButton(canvas, target, "+", sbx + sbw + stepGap, sby, sbw, 80f,
            Color.parseColor("#546E7A"), Color.parseColor("#37474F"), "master_think_inc")
        buttonTextPaint.textSize = 36f
        buttonTextPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText("${masterThinkSeconds} 秒", px + pw / 2f, sby + 80f / 2f + 18f, buttonTextPaint)

        // 底部说明（仅保留该段文字，分两行以免超出面板宽度）
        // 下移并放大字号：避免第一行字顶侵入上方步进按钮（步进按钮底在 py+600）
        buttonTextPaint.textSize = 28f
        buttonTextPaint.color = Color.parseColor("#9E9E9E")
        canvas.drawText("（农民队友ai设为普通，", px + pw / 2f, py + ph - 60f, buttonTextPaint)
        canvas.drawText("玩家难度更高）", px + pw / 2f, py + ph - 22f, buttonTextPaint)
    }

    /** 策略二选一开关：选中项高亮（绿），未选中灰 */
    private fun drawStrategyToggle(canvas: Canvas, target: MutableList<ButtonRect>,
                                    x: Float, y: Float, w: Float, h: Float, gap: Float,
                                    leftLabel: String, rightLabel: String, current: Difficulty,
                                    leftAction: String, rightAction: String) {
        val leftSelected = current == Difficulty.NORMAL
        val rightSelected = current == Difficulty.MASTER
        val leftColor = if (leftSelected) Color.parseColor("#388E3C") else Color.parseColor("#546E7A")
        val leftPressed = if (leftSelected) Color.parseColor("#1B5E20") else Color.parseColor("#37474F")
        val rightColor = if (rightSelected) Color.parseColor("#388E3C") else Color.parseColor("#546E7A")
        val rightPressed = if (rightSelected) Color.parseColor("#1B5E20") else Color.parseColor("#37474F")
        addButton(canvas, target, leftLabel, x, y, w, h, leftColor, leftPressed, leftAction)
        addButton(canvas, target, rightLabel, x + w + gap, y, w, h, rightColor, rightPressed, rightAction)
    }

    /** 二次确认弹窗：确认/取消重置某模式统计 */
    private fun drawConfirmDialog(canvas: Canvas, target: MutableList<ButtonRect>, mode: Difficulty) {
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), modalDimPaint)
        val cw = minOf(screenWidth * 0.62f, 600f)
        val ch = 300f
        val cx0 = (screenWidth - cw) / 2f
        val cy0 = (screenHeight - ch) / 2f
        val panel = RectF(cx0, cy0, cx0 + cw, cy0 + ch)
        canvas.drawRoundRect(panel, 24f, 24f, panelPaint)
        canvas.drawRoundRect(panel, 24f, 24f, panelBorderPaint)

        val label = if (mode == Difficulty.MASTER) "大师" else "普通"
        buttonTextPaint.textSize = 40f
        buttonTextPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText("确认重置${label}模式", cx0 + cw / 2f, cy0 + 70f, buttonTextPaint)
        canvas.drawText("统计数据？", cx0 + cw / 2f, cy0 + 120f, buttonTextPaint)

        val bw = (cw - 120f - 40f) / 2f
        val bh = 84f
        val by = cy0 + ch - bh - 36f
        val confirmX = cx0 + 60f
        val cancelX = confirmX + bw + 40f
        addButton(canvas, target, "确认", confirmX, by, bw, bh,
            Color.parseColor("#D32F2F"), Color.parseColor("#B71C1C"), "confirm_reset")
        addButton(canvas, target, "取消", cancelX, by, bw, bh,
            Color.parseColor("#616161"), Color.parseColor("#424242"), "cancel_reset")
    }

    /** 二次确认弹窗：确认/取消导入备份（覆盖当前设置与统计） */
    private fun drawImportConfirmDialog(canvas: Canvas, target: MutableList<ButtonRect>) {
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), modalDimPaint)
        val cw = minOf(screenWidth * 0.62f, 600f)
        val ch = 300f
        val cx0 = (screenWidth - cw) / 2f
        val cy0 = (screenHeight - ch) / 2f
        val panel = RectF(cx0, cy0, cx0 + cw, cy0 + ch)
        canvas.drawRoundRect(panel, 24f, 24f, panelPaint)
        canvas.drawRoundRect(panel, 24f, 24f, panelBorderPaint)

        buttonTextPaint.textSize = 40f
        buttonTextPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText("确认导入备份？", cx0 + cw / 2f, cy0 + 70f, buttonTextPaint)
        canvas.drawText("将覆盖当前设置与统计", cx0 + cw / 2f, cy0 + 120f, buttonTextPaint)

        val bw = (cw - 120f - 40f) / 2f
        val bh = 84f
        val by = cy0 + ch - bh - 36f
        val confirmX = cx0 + 60f
        val cancelX = confirmX + bw + 40f
        addButton(canvas, target, "确认", confirmX, by, bw, bh,
            Color.parseColor("#388E3C"), Color.parseColor("#1B5E20"), "confirm_import")
        addButton(canvas, target, "取消", cancelX, by, bw, bh,
            Color.parseColor("#616161"), Color.parseColor("#424242"), "cancel_import")
    }

    /** 消息提示 */
    private fun drawMessage(canvas: Canvas) {
        if (messageText.isEmpty()) return
        if (System.currentTimeMillis() > messageTimer) { messageText = ""; return }

        textPaint.textSize = 48f
        textPaint.color = Color.parseColor("#FFD600")
        textPaint.textAlign = Paint.Align.CENTER

        val tw = textPaint.measureText(messageText)
        val msgRect = RectF(
            screenWidth / 2 - tw / 2 - 40f, screenHeight * 0.18f,
            screenWidth / 2 + tw / 2 + 40f, screenHeight * 0.18f + 72f
        )
        canvas.drawRoundRect(msgRect, 16f, 16f, msgBgPaint)
        canvas.drawText(messageText, screenWidth / 2f, screenHeight * 0.18f + 54f, textPaint)
    }

    /** 错误提示（红色） */
    private fun drawError(canvas: Canvas) {
        if (errorText.isEmpty()) return
        if (System.currentTimeMillis() > errorTimer) { errorText = ""; return }

        textPaint.textSize = 48f
        textPaint.color = Color.parseColor("#FF5252")
        textPaint.textAlign = Paint.Align.CENTER

        val tw = textPaint.measureText(errorText)
        val errRect = RectF(
            screenWidth / 2 - tw / 2 - 40f, screenHeight * 0.26f,
            screenWidth / 2 + tw / 2 + 40f, screenHeight * 0.26f + 72f
        )
        canvas.drawRoundRect(errRect, 16f, 16f, errBgPaint)
        canvas.drawText(errorText, screenWidth / 2f, screenHeight * 0.26f + 54f, textPaint)
    }

    /** 右上角统计面板：模式(普通/大师) + 胜率 + 积分，按模式分开统计；主界面不显示，进入游戏后显示 */
    private fun drawStats(canvas: Canvas) {
        // 主界面(MENU)不显示状态栏，选择难度进入游戏后再显示
        if (gameEngine.stateMachine.phase == GamePhase.MENU) return

        val stat = modeStats[displayMode] ?: ModeStat()
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 36f                            // 模式与胜率/积分统一字号
        textPaint.color = Color.parseColor("#FFFFFF")

        val y = 56f
        val gap = 28f                                      // 积分与胜率之间的间距
        val charSpace = textPaint.measureText("　")         // 一个全角字空格，置于胜率与模式之间

        val scoreText = "积分: ${stat.score}"
        val rate = if (stat.games > 0) (stat.wins * 100.0 / stat.games) else 0.0
        // 胜率格式保留：胜率:胜场/总场 (胜率%)
        val rateText = "胜率:${stat.wins}/${stat.games} (${"%.0f".format(rate)}%)"
        val modeText = if (displayMode == Difficulty.MASTER) "大师" else "普通"

        val sScore = textPaint.measureText(scoreText)
        val sRate = textPaint.measureText(rateText)
        val sMode = textPaint.measureText(modeText)

        // 整体（模式 + 胜率 + 积分）水平居中于顶部状态栏
        val totalW = sMode + charSpace + sRate + gap + sScore
        val startX = (screenWidth - totalW) / 2f

        // 模式（最左）
        textPaint.color = Color.parseColor("#EF5350")      // 红字
        canvas.drawText(modeText, startX, y, textPaint)
        // 胜率（模式右侧）
        val rateLeft = startX + sMode + charSpace
        textPaint.color = Color.parseColor("#FFFFFF")
        canvas.drawText(rateText, rateLeft, y, textPaint)
        // 积分（胜率右侧）
        val scoreLeft = rateLeft + sRate + gap
        canvas.drawText(scoreText, scoreLeft, y, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
    }

    /** 绘制线程 */
    private class DrawThread(
        private val holder: SurfaceHolder,
        private val view: GameSurfaceView
    ) : Thread("GameDrawThread") {
        @Volatile var running = true
        override fun run() {
            while (running) {
                // 空闲时（无变化、无动画）进入低频等待，节省 CPU/电量
                if (!view.shouldDraw()) {
                    try { sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(holder) { view.drawGame(canvas) }
                        view.onFrameDrawn()
                    }
                } catch (_: Exception) {} finally {
                    canvas?.let { holder.unlockCanvasAndPost(it) }
                }
                try { sleep(33) } catch (_: InterruptedException) { break }
            }
        }
    }
}
