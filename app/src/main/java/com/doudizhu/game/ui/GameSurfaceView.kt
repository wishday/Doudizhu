package com.doudizhu.game.ui

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.doudizhu.game.logic.GameEngine
import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.state.GamePhase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * 游戏主绘制视图（横屏模式）
 * 布局：左右两侧AI，底部玩家手牌，中央桌面展示所有出牌
 * 支持：滑动选牌、当前回合高亮、计分显示、音效反馈、按钮按压反馈
 */
class GameSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** 游戏引擎引用 */
    lateinit var gameEngine: GameEngine

    /** 渲染节奏由 Choreographer 的 vsync 帧回调驱动（替换原 Thread.sleep 轮询，消除掉帧/抖动） */
    private val choreographer: Choreographer = Choreographer.getInstance()
    @Volatile private var frameScheduled = false
    /** Surface 是否有效（destroyed 后置 false，避免向已销毁的 Surface 提交画布） */
    @Volatile private var surfaceAlive = false
    /** 有待重绘请求（refresh 置位，绘制后清除；多次 refresh 合并为一帧） */
    @Volatile private var pendingRedraw = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameCallback = Choreographer.FrameCallback { _ -> onFrame() }

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
    private var uiScale = 1f
    private var topBarH = 84f

    private fun recalcSizes() {
        val scale = (screenWidth.toFloat() / 1920f).coerceIn(0.85f, 1.5f)
        uiScale = scale
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

    // ====== 语义化色板（集中管理主题色，便于统一调色与暗/亮主题扩展） ======
    private object Palette {
        // 文本与点数字色（用 0xRRGGBB 整型字面量；注意 Kotlin 中 0xFFxxxxxx 超过 Int.MAX_VALUE
        // 会被推断为 Long，故统一 .toInt() 取回与 Color.parseColor 相同的 32 位 ARGB Int）
        val rankRed = 0xFFD32F2F.toInt()
        val rankBlack = 0xFF212121.toInt()
        val textLightGray = 0xFFD9D9D9.toInt()
        val textGreen = 0xFFA5D6A7.toInt()
        val gold = 0xFFFFD600.toInt()
        val bubbleText = 0xFFECEFF1.toInt()
        val modeRed = 0xFFEF5350.toInt()
        val errText = 0xFFFF5252.toInt()
        // 卡牌/面板描边
        val cardBorder = 0xFFC9CFD6.toInt()
        val cardBorderSelected = 0xFFFFC107.toInt()
        val passBorder = Color.argb(60, 176, 190, 197)
        // 浮层玻璃（统一：带微暖/微绿偏移的半透明 + 暖金高光描边）
        val glassFill = Color.argb(150, 12, 34, 22)
        val glassDrop = Color.argb(70, 0, 0, 0)
        val glassBorder = Color.argb(90, 255, 236, 179)
        val dimFill = Color.argb(170, 0, 0, 0)
        val bubbleFill = 0xFF37474F.toInt()
        val errFill = Color.argb(221, 0, 0, 0)
        val tableOvalStroke = Color.argb(40, 255, 236, 179)
        // 强调色
        val highlightYellow = 0xFFFFEB3B.toInt()
        val highlightGlow = 0x30FFEB3B.toInt()
        val aiHighlight = Color.argb(120, 255, 152, 0)
        // 装饰
        val ovalDecor = 0xFF15491A.toInt()
        val highlightWhite = Color.argb(32, 255, 255, 255)
        val bannerStroke = Color.argb(120, 255, 214, 90)
    }

    // ====== 画笔 ======
    // isDither：软件渲染下对渐变做有序抖动，消除 8-bit 量化导致的色阶带（背景/光晕/暗角）。
    // 注意：bgPaint / vignettePaint 仅用于绘制渐变 shader；ovalPaint 还兼画纯色椭圆装饰，
    // 故不给 ovalPaint 开启 dither——有序抖动会让纯色填充出现可见的网点纹理。
    private val bgPaint = Paint().apply { style = Paint.Style.FILL; isDither = true }
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
        color = Palette.highlightYellow
    }
    /** 高亮发光效果 */
    private val glowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL
        color = Palette.highlightGlow
    }

    // ====== 缓存的装饰画笔（避免每帧分配 Paint 对象） ======
    private val ovalPaint = Paint().apply {
        // 不加 isDither：此画笔兼画纯色椭圆装饰（见 drawBackground），抖动会产生网点纹理；
        // 桌心柔光的渐变抖动已由 bgPaint / vignettePaint 覆盖
        color = Palette.ovalDecor; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val topBarPaint = Paint().apply { color = Color.parseColor("#80000000"); style = Paint.Style.FILL }
    private val aiInfoPaint = Paint().apply { color = Color.parseColor("#60000000"); style = Paint.Style.FILL }
    private val buttonOverlayPaint = Paint().apply {
        color = Color.parseColor("#30000000"); style = Paint.Style.FILL
    }
    private val buttonHighlightPaint = Paint().apply {
        color = Palette.highlightWhite; style = Paint.Style.FILL
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
    /** 错误提示弹窗背景（深红半透明圆角底） */
    private val errBgPaint = Paint().apply { color = Palette.errFill; style = Paint.Style.FILL }
    private val aiHighlightPaint = Paint().apply {
        color = Palette.aiHighlight; style = Paint.Style.FILL
    }
    private val modalDimPaint = Paint().apply { color = Palette.dimFill; style = Paint.Style.FILL }
    private val panelPaint = Paint().apply { color = Palette.glassFill; style = Paint.Style.FILL }
    private val panelBorderPaint = Paint().apply {
        color = Palette.glassBorder; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val gearPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.parseColor("#B0BEC5") }
    private val gearHolePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.parseColor("#0D3B0D") }
    /** ====== 细腻化绘制新增画笔 ====== */
    /** 牌面微渐变（象牙白 → 冷白） */
    private val cardFaceShader = LinearGradient(
        0f, 0f, 0f, 1f,
        Color.parseColor("#FFFDF8"), Color.parseColor("#F1F4F9"),
        Shader.TileMode.CLAMP
    )
    /** 牌背主体渐变（深蓝 → 靛蓝） */
    private val cardBackShader = LinearGradient(
        0f, 0f, 1f, 1f,
        Color.parseColor("#1E67C8"), Color.parseColor("#0C3E86"),
        Shader.TileMode.CLAMP
    )
    /** 按钮顶部光泽渐变 */
    private val buttonGlossPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    /** 按钮描边 */
    private val buttonStrokePaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f
        color = Color.argb(70, 255, 255, 255)
    }
    /** 文字投影画笔（柔和阴影，提升可读性与层次） */
    private val textShadowPaint = Paint().apply {
        isAntiAlias = true; color = Color.argb(90, 0, 0, 0)
    }
    /** 桌面柔光（中央椭圆径向渐变） */
    private var tableGlow: RadialGradient? = null
    private var tableGlowKey = ""
    /** 选中牌的金色光晕 */
    private val selectGlowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    /** 回合呼吸光晕（径向渐变缓存） */
    private val breathGlowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    /** 结算横幅底漆（描边画笔会改色，故显式指定白色，避免继承残留色） */
    private val bannerPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.WHITE }
    private val bannerStrokePaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 3f
        color = Palette.bannerStroke
    }
    /** 面板头部条带 */
    private val panelHeaderPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    /** 背景渐变缓存（仅尺寸变化时重建） */
    private var bgGradient: LinearGradient? = null
    private var bgGradientW = 0f
    private var bgGradientH = 0f
    /** 顶栏渐变缓存（尺寸不变不重建，避免每帧 new LinearGradient） */
    private var topBarShader = LinearGradient(
        0f, 0f, 0f, 84f * 1.6f,
        Palette.glassFill, Color.TRANSPARENT,
        Shader.TileMode.CLAMP
    )
    /** AI 信息面板玻璃底渐变缓存（面板纵向位置固定） */
    private val aiPanelShader = LinearGradient(
        0f, 100f, 0f, 100f + 180f,
        Color.argb(120, 18, 44, 26), Color.argb(96, 8, 24, 14),
        Shader.TileMode.CLAMP
    )
    /** 绿呢桌布细噪点纹理（一次性生成，位图平铺，成本极低） */
    private val clothNoiseBitmap: Bitmap? by lazy { buildClothNoise() }
    private val noisePaint = Paint().apply {
        isAntiAlias = false; style = Paint.Style.FILL
    }
    /** 暗角径向渐变缓存（屏幕尺寸变化时重建） */
    private var vignette: RadialGradient? = null
    private var vignetteKey = ""
    private val vignettePaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL; isDither = true }

    // ====== 新增：呼吸光晕/「不出」气泡/花色符号所需状态与画笔 ======
    /** 回合呼吸光晕 shader 缓存（半径/alpha 量化后按需重建，避免每帧分配） */
    private var breathGlowShader: RadialGradient? = null
    private var breathGlowKey = ""
    /** 复用矩形（避免逐帧 new RectF 产生 GC 压力） */
    private val scratchRect = RectF()
    /** 选中金色光晕 shader 缓存（按量化 alpha 重建，稳态选中不重建） */
    private var selectGlowShader: RadialGradient? = null
    private var selectGlowKey = ""
    /** 「不出」气泡底漆（胶囊 + 尾尖共用，随动画淡入调整 alpha） */
    private val passBubblePaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL; color = Palette.bubbleFill
    }
    /** 「不出」气泡尾尖路径（复用，避免每帧 new Path） */
    private val passTrianglePath = Path()
    /** 花色符号画笔（普通字体：粗体字库常缺 ♠♥♣♦ 字形导致"方块"显示为空白） */
    private val suitSymbolPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT
    }

    /** 按钮区域（绘制线程每帧整体重建后原子发布，触摸线程始终读到完整列表） */
    data class ButtonRect(val text: String, val rect: RectF, val color: Int, val pressedColor: Int, val action: String)
    @Volatile
    private var currentButtons: List<ButtonRect> = emptyList()
    /** 当前按下的按钮动作（按下时记录，释放时执行，避免按钮列表重建导致索引错位） */
    private var pressedAction: String? = null

    /** 所有玩家的桌面出牌展示（0=人类, 1=右AI, 2=左AI）；同一时刻仅展示最新一手 */
    private val tablePlayedCards = arrayOfNulls<List<Card>>(3)
    /** 退场动画：出牌瞬间捕获"桌上正在展示的那一手"（无论属于哪个槽位）作为退场对象，
     *  在其自身槽位坐标处下移 + 淡出，与新牌入场同步（覆盖"下家出牌、上家旧牌退出"的常见场景） */
    private var outCards: List<Card>? = null
    private var outSlot = -1
    private var outAnimStartMs = 0L

    /** 电脑「思考中」提示字号（出牌阶段与叫分阶段统一使用 44f，不再放大） */
    private val AI_THINKING_TIP_TEXT_SIZE = 44f

    /** 「不出」气泡文字字号：与「电脑A/B思考中」一致（44f） */
    private val PASS_BUBBLE_TEXT_SIZE = AI_THINKING_TIP_TEXT_SIZE

    /** 「不出」气泡状态：各玩家槽位最近一次过牌的时间戳与代号（-1 表示无气泡；新一轮由 clearAllPlayedCards 清除） */
    private val passAnimStart = LongArray(3)
    private val passSlotEpoch = IntArray(3) { -1 }

    /** 叫分阶段提示字号：与「不出」气泡、「电脑A/B思考中」文字一致（44f） */
    private val AI_BID_TIP_TEXT_SIZE = PASS_BUBBLE_TEXT_SIZE

    /** 过牌代号计数器：每次任意玩家过牌自增，用于区分"连续两次不出"与新的一手 */
    private var passEpochCounter = 0
    /** 「不出」气泡淡入动画时长（ms）：仅用于驱动淡入期间的持续重绘；气泡本身不自动消退 */
    private val PASS_BUBBLE_FADEIN_MS = 450L
    /** 旧牌退场动画时长（ms）：与逐张落桌入场(~400ms)同步，略短以留出"完全消失"的余量 */
    private val OUT_ANIM_DUR = 380L

    /** 注册一次「不出」气泡动画（由 MainActivity 在玩家过牌时调用）。
     *  过牌即"行动完毕"：同时清除该玩家槽位的桌面出牌展示，避免旧牌与其「不出」同屏矛盾；
     *  并清空其它玩家的过期气泡（轮到谁、谁的气泡就该消失），防止残留误导。 */
    fun registerPassBubble(playerIndex: Int) {
        if (playerIndex !in 0..2) return
        tablePlayedCards[playerIndex] = null
        playAnimEnd[playerIndex] = 0L
        for (i in passSlotEpoch.indices) {
            if (i != playerIndex) passSlotEpoch[i] = -1
        }
        passAnimStart[playerIndex] = System.currentTimeMillis()
        passSlotEpoch[playerIndex] = ++passEpochCounter
        refresh()
    }

    /** 叫分阶段状态变化（由 MainActivity 在叫分开始 / 某玩家叫分时调用）：仅触发一次重绘；
     *  绘制时直接读取引擎状态，无需 UI 侧快照或墙钟保护。 */
    fun updateBidDisplay() {
        if (!::gameEngine.isInitialized) return
        refresh()
    }

    /** 「xx成为地主」中央弹窗（所有人叫分完成后弹出，保持 1.5s 后自动关闭并触发 onDismiss）。
     *  @Volatile：绘制线程读取、UI 线程写入；到期清除时须再 refresh 一次，防止静止画面残留。 */
    @Volatile
    private var landlordPopupText = ""
    @Volatile
    private var landlordPopupUntil = 0L
    private var onLandlordPopupDismiss: Runnable? = null
    /** 弹窗自动关闭的待执行 Runnable：由主线程定时器驱动而非逐帧绘制，解除对前台帧渲染的依赖 */
    private var landlordPopupCloseRunnable: Runnable? = null
    /** 地主弹窗展示时长（ms）：保持 1.5s 后关闭，随后进入正常出牌阶段 */
    private val LANDLORD_POPUP_MS = 1500L

    /** 通用中央提示弹窗（复用「xx成为地主」同款样式与绘制逻辑）：
     *  用于叫分开始前提示「xx开始叫地主」等，保持指定时长后自动关闭并触发 onDismiss。
     *  注意：会覆盖当前正在展示的地主弹窗文本（设计上两者时序互斥，不会并发使用）。 */
    fun showCenterPopup(text: String, durationMs: Long, onDismiss: () -> Unit) {
        landlordPopupText = text
        landlordPopupUntil = System.currentTimeMillis() + durationMs
        // onDismiss 经主线程 Handler 投递，避免 View.post 在窗口 detached 时不执行
        onLandlordPopupDismiss = Runnable { mainHandler.post(onDismiss) }
        // 取消上一次未触发的关闭任务，防止串场/重复关闭
        landlordPopupCloseRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { closeLandlordPopup() }
        landlordPopupCloseRunnable = runnable
        // 关键修复：用主线程定时器在到期时主动关闭弹窗。该定时器不依赖 Choreographer 逐帧绘制，
        // 即使窗口不可见（最小化/锁屏/被覆盖）也会照常触发，从而解除「卡在 xx开始叫地主」的根因。
        mainHandler.postDelayed(runnable, durationMs)
        refresh()
    }

    /** 统一关闭中央弹窗：清空文本、触发 onDismiss、请求重绘。幂等（已关闭则直接返回）。 */
    private fun closeLandlordPopup() {
        if (landlordPopupText.isEmpty()) return
        landlordPopupText = ""
        val cb = onLandlordPopupDismiss
        onLandlordPopupDismiss = null
        landlordPopupCloseRunnable = null
        cb?.run()
        // 非逐帧阶段若仅 return 不刷新，弹窗会残留在最后一帧画面上，必须再触发一次重绘
        refresh()
    }

    /** 显示「xx成为地主」弹窗（由 MainActivity 在叫分全部完成时调用）；
     *  弹窗到期自动关闭后执行 onDismiss（进入出牌阶段）。重复调用以最后一次为准。 */
    fun showLandlordPopup(name: String, onDismiss: () -> Unit) {
        showCenterPopup("${name}成为地主", LANDLORD_POPUP_MS, onDismiss)
    }

    /** 地主/中央提示弹窗是否仍在展示期内（供 shouldKeepDrawing 持续重绘以便到期自动消失） */
    private fun landlordPopupActive(): Boolean =
        landlordPopupText.isNotEmpty() && System.currentTimeMillis() <= landlordPopupUntil



    /** 绘制「xx成为地主」中央弹窗：屏幕正中深色圆角底 + 金色大字；到期清除并触发关闭回调 */
    private fun drawLandlordPopup(canvas: Canvas) {
        val text = landlordPopupText
        if (text.isEmpty()) return
        // 兜底：若某帧已绘制且发现已超时（定时器偶发晚于绘制触发），交由统一关闭入口处理；
        // 正常情况下由 closeLandlordPopup 定时器主动关闭，此处仅为双保险。
        if (System.currentTimeMillis() > landlordPopupUntil) {
            closeLandlordPopup()
            return
        }

        textPaint.textSize = 56f
        textPaint.color = Palette.gold
        textPaint.textAlign = Paint.Align.CENTER

        val tw = textPaint.measureText(text)
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val rect = RectF(cx - tw / 2 - 70f, cy - 80f, cx + tw / 2 + 70f, cy + 80f)
        canvas.drawRoundRect(rect, 24f, 24f, errBgPaint)
        drawTextWithShadow(canvas, text, cx, cy + 20f, textPaint)
    }

    /** 错误提示（红色弹窗）：仅在非法操作时弹出，展示到期后自动清除 */
    private var errorText = ""
    private var errorTimer = 0L
    /** 错误提示自动清除的待执行 Runnable：主线程定时器驱动，解除对帧渲染的依赖 */
    private var errorCloseRunnable: Runnable? = null

    /** 滑动选牌状态 */
    private var isDragging = false
    /** 按下时的起始坐标（用于区分点击/滑动、防误触） */
    private var downX = 0f
    private var downY = 0f
    /** 点击与滑动的位移阈值（防误触：小于该距离视为点击） */
    private val tapSlop = 24f
    /** 本次拖拽中最近处理过的牌索引（防止手指停留同一张牌时反复切换） */
    private var lastDragCardIndex = -1
    /** 上次 MOVE 触点坐标（用于线段覆盖补选，解决快速滑动跳牌漏选） */
    private var lastDragX = 0f
    private var lastDragY = 0f
    /** 本次连续滑动中已切换过的牌索引（去重：每张牌在一次滑动内只切换一次） */
    private val dragHandled = mutableSetOf<Int>()

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
    /** 大师模式 AI 最大思考时间（秒），本地显示状态，2~60，默认 5；仅在关闭设置时落盘 */
    private var masterThinkSeconds: Int = 5
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

    /** 当前回合高亮动画帧（0..59，绘制线程每帧自增） */
    private var highlightFrame = 0
    /** 全局动画时间戳（ms），用于呼吸/脉冲等连续动画 */
    private var animTime = 0L
    /** 平滑缓动：当前值向目标值过渡（选中牌上浮等） */
    private val liftValues = FloatArray(40)
    /** 桌面出牌入场动画起始时间（按玩家槽位） */
    private val playAnimStart = LongArray(3)
    /** 桌面出牌落桌入场动画结束时间（用于精准判断动画是否仍在进行，避免 PLAYING 停帧误杀） */
    private val playAnimEnd = LongArray(3)

    /** 结算横幅状态：本局人类是否获胜 + 进入结算的时间戳（滑入动画起点，由 MainActivity 在 onGameOver 时调用） */
    @Volatile
    private var lastRoundHumanWin = false
    @Volatile
    private var gameOverAnimStart = 0L
    /** 结算积分信息（合并进结算横幅展示，替代原独立消息提示框）：本局得分变化与倍数说明 */
    @Volatile
    private var lastRoundScoreDelta = 0
    @Volatile
    private var lastRoundMultiplierText = ""

    /** 记录结算结果并触发横幅滑入动画；scoreDelta/springTag 为结算积分提示文案（并入横幅显示） */
    fun showSettlement(humanWin: Boolean, scoreDelta: Int = 0, multiplierText: String = "") {
        lastRoundHumanWin = humanWin
        lastRoundScoreDelta = scoreDelta
        lastRoundMultiplierText = multiplierText
        gameOverAnimStart = System.currentTimeMillis()
        // 结算时保留最后一手打出的牌：桌面出牌展示与「不出」气泡原样留在桌上不清空，
        // 重开一局/返回主界面时才由 clearAllPlayedCards 清除
        refresh()
    }

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

    /** 音效引擎（程序化合成 PCM，AudioTrack 播放；ToneGenerator 兜底已内置其中） */
    private var soundEngine: SoundEngine? = null
    private var vibrator: Vibrator? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        initAudio()
    }

    /** 初始化音效/振动（surface 重建后也会重新调用） */
    private fun initAudio() {
        try {
            if (soundEngine == null) soundEngine = SoundEngine()
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

    /** 统一音效入口（委托给合成引擎；引擎未就绪则静默） */
    private fun sfx(block: (SoundEngine) -> Unit) {
        try { soundEngine?.let(block) } catch (_: Exception) {}
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
        sfx { it.lastCardAlert() }
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

    /** 播放出牌音效（按牌型区分：炸弹/火箭为爆炸轰鸣，其余为甩牌"唰"声） */
    fun playCardSound(isBomb: Boolean = false) {
        if (isBomb) sfx { it.bomb() } else sfx { it.cardPlay() }
    }

    /** 播放错误音效 */
    fun playErrorSound() {
        sfx { it.error() }
        vibrate(VibrationKind.HEAVY)
    }

    /** 播放胜利音效（上行琶音） */
    fun playWinSound() {
        sfx { it.win() }
    }

    /** 播放失败音效（下行三连音，柔和不刺耳） */
    fun playLoseSound() {
        sfx { it.lose() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenWidth = width
        screenHeight = height
        recalcSizes()
        initAudio()
        startRendering()    // 重新进入应用时启动 vsync 渲染（首帧由 Choreographer 触发，避免静态界面黑屏）
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        recalcSizes()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
        soundEngine?.stop()
        soundEngine?.release()
        soundEngine = null
    }

    fun startRendering() {
        surfaceAlive = true
        pendingRedraw = true
        scheduleFrame()
    }

    fun stopRendering() {
        surfaceAlive = false
        frameScheduled = false
        pendingRedraw = false
        choreographer.removeFrameCallback(frameCallback)
    }

    /** 请求重绘：状态变化/触摸事件调用；可在任意线程调用（非主线程经 Handler 投递）。
     *  仅置位 pendingRedraw 并幂等注册下一帧，不计数——多次 refresh 合并为一帧。 */
    fun refresh() {
        pendingRedraw = true
        if (Looper.myLooper() == Looper.getMainLooper()) scheduleFrame()
        else mainHandler.post { scheduleFrame() }
    }

    /** 幂等注册下一帧 vsync 回调，避免重复注册导致一帧多次绘制 */
    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    /** 每帧 vsync 回调：对齐系统刷新节奏，消除原 Thread.sleep 轮询的掉帧/抖动 */
    private fun onFrame() {
        frameScheduled = false
        if (!surfaceAlive) { pendingRedraw = false; return }
        val need = pendingRedraw || shouldKeepDrawing()
        if (need) {
            drawFrame()
            pendingRedraw = false
        }
        // 仍有动画/弹窗需持续刷新才续帧，否则停帧省电
        if (surfaceAlive && shouldKeepDrawing()) {
            frameScheduled = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    /** 锁定画布绘制一帧（在主线程 Choreographer 回调中执行，单线程无需额外加锁） */
    private fun drawFrame() {
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) drawGame(canvas)
        } catch (_: Exception) {
        } finally {
            canvas?.let { holder.unlockCanvasAndPost(it) }
        }
    }

    /** 是否仍需持续绘制：有待重绘请求，或存在需逐帧刷新的动画/弹窗。
     *  出牌阶段（PLAYING）仅在「有动画在播」时持续刷新；人类回合静止画面会停帧（省电），
     *  动画播完或人类出牌后自动停帧，下一次触摸/状态变化由 refresh 唤醒。 */
    fun shouldKeepDrawing(): Boolean {
        if (!::gameEngine.isInitialized) return isHandReveal || landlordPopupActive()
        if (isHandReveal) return true
        val phase = gameEngine.stateMachine.phase
        // 出牌阶段：呼吸光晕/思考省略号/落桌入场/选中上浮等动画在播时持续刷新
        if (phase == GamePhase.PLAYING) return playingHasAnimation()
        // 叫分阶段画面静止（已无逐帧动画），仅弹窗活动期刷新
        if (landlordPopupActive()) return true
        for (i in passSlotEpoch.indices) {
            if (passSlotEpoch[i] >= 0 && System.currentTimeMillis() - passAnimStart[i] < PASS_BUBBLE_FADEIN_MS) return true
        }
        if ((phase == GamePhase.GAME_OVER || phase == GamePhase.SETTLING) && bannerActive()) return true
        return hintActive(errorText, errorTimer)
    }

    /** PLAYING 阶段是否存在需逐帧刷新的动画/弹窗（决定能否停帧）。
     *  注意：地主/中央弹窗与错误提示虽非「动画」，但需在展示期内持续重绘以便到期自动关闭/消失，
     *  否则静止画面会永久残留旧提示；原 shouldDraw 对 PLAYING 无条件返回 true 已隐含覆盖，此处需显式补回。 */
    private fun playingHasAnimation(): Boolean {
        // 地主/中央提示弹窗展示期内需持续重绘，以便到期自动关闭并触发进入出牌阶段
        if (landlordPopupActive()) return true
        // 错误提示弹窗展示期内需持续重绘，以便到期后自动消失（否则静止画面会永久残留）
        if (hintActive(errorText, errorTimer)) return true
        val sm = gameEngine.stateMachine
        // 当前为 AI 回合：呼吸光晕 + 「思考中」省略号需逐帧刷新
        if (sm.currentPlayerIndex != 0) return true
        // 任一玩家桌面牌落桌入场动画进行中
        if (playAnimActive()) return true
        // 上一手退场动画进行中（下移 + 淡出），需持续重绘直至完成
        if (outCards != null) return true
        // 「不出」气泡淡入中
        for (i in passSlotEpoch.indices) {
            if (passSlotEpoch[i] >= 0 && System.currentTimeMillis() - passAnimStart[i] < PASS_BUBBLE_FADEIN_MS) return true
        }
        // 人类手牌选中上浮缓动尚未收敛
        if (humanLiftAnimating()) return true
        return false
    }

    /** 桌面出牌落桌入场动画是否仍在进行（以动画结束时间为准，精准不误杀） */
    private fun playAnimActive(): Boolean {
        for (i in playAnimEnd.indices) {
            if (playAnimEnd[i] > 0 && animTime < playAnimEnd[i]) return true
        }
        return false
    }

    /** 人类手牌选中上浮缓动是否仍在向目标值靠拢（未收敛则仍在动画） */
    private fun humanLiftAnimating(): Boolean {
        val hand = gameEngine.players[0].handCards
        val sel = gameEngine.selectedCardIndices
        for (i in hand.indices) {
            val target = if (i in sel) 60f else 0f
            if (kotlin.math.abs(liftValues[i] - target) >= 0.5f) return true
        }
        return false
    }


    /** 提示弹窗是否仍在展示期内（供 shouldDraw 判断是否需要持续重绘以自动消失） */
    private fun hintActive(text: String, timer: Long): Boolean =
        text.isNotEmpty() && System.currentTimeMillis() <= timer

    /** 结算横幅是否仍处于活动期（滑入 450ms + 停留 3 秒 + 淡出 400ms，共约 4.05 秒；结束后停止重绘省电）。
     *  必须用 currentTimeMillis：横幅静止后不再有 refresh，animTime 会冻结在最后一帧的旧值，
     *  若用 animTime 判断则 elapsed 永远偏小 → shouldKeepDrawing 恒真 → 停帧逻辑失效、持续耗电。 */
    private fun bannerActive(): Boolean =
        gameOverAnimStart > 0L && System.currentTimeMillis() - gameOverAnimStart < BANNER_TOTAL_MS

    /** 结算横幅时间轴：滑入 450ms + 停留 3000ms + 淡出 400ms */
    private val BANNER_SLIDE_MS = 450L
    private val BANNER_HOLD_MS = 3000L
    private val BANNER_FADE_MS = 400L
    private val BANNER_TOTAL_MS = BANNER_SLIDE_MS + BANNER_HOLD_MS + BANNER_FADE_MS

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

    /** 非法操作统一反馈（牌型不合法/打不过上家/未选牌/无牌可出）：
     *  弹出红色文字提示框 + 原有错误音效与振动提示 */
    private fun rejectPlay(msg: String, durationMs: Long = 2000) {
        errorText = msg
        errorTimer = System.currentTimeMillis() + durationMs
        playErrorSound()
        errorCloseRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { closeError() }
        errorCloseRunnable = runnable
        // 关键修复：错误提示的清除同样改用主线程定时器，不再依赖逐帧绘制检测超时，
        // 避免窗口不可见时红色提示滞留，且恢复后仍能正确清除。
        mainHandler.postDelayed(runnable, durationMs)
        refresh()
    }

    /** 统一清除错误提示：清空文本并请求重绘。幂等。 */
    private fun closeError() {
        if (errorText.isEmpty()) return
        errorText = ""
        errorCloseRunnable = null
        refresh()
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

    /** 设置任意玩家的桌面出牌展示（新牌入手时记录动画起始时间，用于入场动效）；
     *  出牌瞬间捕获当前桌上正在展示的那一手牌作为退场对象（无论属于哪个槽位），
     *  清掉所有槽位后展示新手牌——从而覆盖"下家出牌、上家旧牌退出"的常见过渡场景；
     *  同时清除其它玩家残留的「不出」气泡（气泡不自动消退，由此处显式清理）。
     *  注意：调用方（onPlayerPlay / 人类出牌）不应再预先 clearAllPlayedCards，
     *  否则此处无法捕获到上一手牌，退场动画会失效。 */
    fun setTablePlayedCards(playerIndex: Int, cards: List<Card>?) {
        val now = System.currentTimeMillis()
        if (cards != null && cards.isNotEmpty()) {
            // 捕获当前桌上正在展示的那一手牌（来自任意槽位）作为退场动画对象
            val curSlot = tablePlayedCards.indexOfFirst { it != null }
            if (curSlot >= 0 && curSlot != playerIndex) {
                outCards = tablePlayedCards[curSlot]
                outSlot = curSlot
                outAnimStartMs = now
            } else if (curSlot == playerIndex && tablePlayedCards[playerIndex] != cards) {
                // 同一玩家连续两手（如赢墩后再领出）：同槽位退场
                outCards = tablePlayedCards[playerIndex]
                outSlot = playerIndex
                outAnimStartMs = now
            }
            for (i in tablePlayedCards.indices) tablePlayedCards[i] = null
            tablePlayedCards[playerIndex] = cards
            playAnimStart[playerIndex] = now
            playAnimEnd[playerIndex] = now + (cards.size - 1) * 75L + 400L + 80L
            for (i in passSlotEpoch.indices) {
                if (i != playerIndex) passSlotEpoch[i] = -1
            }
        } else {
            for (i in tablePlayedCards.indices) tablePlayedCards[i] = null
            playAnimEnd[playerIndex] = 0L
            outCards = null
            outSlot = -1
            outAnimStartMs = 0L
        }
        refresh()
    }

    /** 清除所有桌面出牌展示（重开一局/返回主界面/开局发牌时；结算时不调用，保留最后一手牌）；
     *  同时清空退场动画对象、全部「不出」气泡。此处为即时清空（无退场动画），
     *  用于新一局或退出等对局状态切换场景。 */
    fun clearAllPlayedCards() {
        for (i in tablePlayedCards.indices) {
            tablePlayedCards[i] = null
            playAnimEnd[i] = 0L
            passSlotEpoch[i] = -1
        }
        outCards = null
        outSlot = -1
        outAnimStartMs = 0L
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
                lastDragX = x
                lastDragY = y
                dragHandled.clear()
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
                    dragToggleSegment(x, y)
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
                    dragHandled.clear()
                    refresh()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                lastDragCardIndex = -1
                dragHandled.clear()
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
            sfx { it.tapDeselect() }
        } else {
            gameEngine.selectedCardIndices.add(idx)
            sfx { it.tapSelect() }
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
            sfx { it.tapDeselect() }
        } else {
            gameEngine.selectedCardIndices.add(idx)
            sfx { it.tapSelect() }
        }
        refresh()
    }

    /**
     * 滑动选牌（优化版）：把从上一触点 (lastDragX,lastDragY) 到当前触点 (x,y) 的线段
     * 穿过的所有手牌都切换一次（每张在本次连续滑动内只切换一次，由 dragHandled 去重）。
     * 解决此前「仅对离散 MOVE 触点做命中、手指快速滑动时跨过中间牌导致漏选/漏取消」的问题；
     * 同时由于线段起点即按下点，起点牌也会被覆盖，避免「按下后小幅滑动导致起点牌漏选」。
     */
    private fun dragToggleSegment(x: Float, y: Float) {
        val hand = gameEngine.players[0].handCards
        val n = hand.size
        if (n == 0) { lastDragX = x; lastDragY = y; return }
        val totalWidth = handSpacing * (n - 1) + cardW
        val startX = (screenWidth - totalWidth) / 2
        val baseY = screenHeight - cardH - 40f
        val minY = baseY - 70f - 24f
        val maxY = baseY + cardH + 24f
        for (i in 0 until n) {
            val cx = startX + i * handSpacing
            var leftBound = Float.NEGATIVE_INFINITY
            var rightBound = Float.POSITIVE_INFINITY
            if (i > 0) leftBound = (startX + (i - 1) * handSpacing + cx) / 2f
            if (i < n - 1) rightBound = (cx + startX + (i + 1) * handSpacing) / 2f
            if (segmentCrossesBand(lastDragX, lastDragY, x, y, leftBound, rightBound, minY, maxY)
                && i !in dragHandled) {
                dragHandled.add(i)
                lastDragCardIndex = i
                if (i in gameEngine.selectedCardIndices) {
                    gameEngine.selectedCardIndices.remove(i)
                    sfx { it.tapDeselect() }
                } else {
                    gameEngine.selectedCardIndices.add(i)
                    sfx { it.tapSelect() }
                }
            }
        }
        lastDragX = x
        lastDragY = y
        refresh()
    }

    /**
     * 线段 (x0,y0)-(x1,y1) 是否与轴对齐矩形 [bx0,bx1]×[by0,by1] 相交（Liang-Barsky 裁剪）。
     * 用于判断滑动轨迹是否「扫过」某张手牌。
     */
    private fun segmentCrossesBand(
        x0: Float, y0: Float, x1: Float, y1: Float,
        bx0: Float, bx1: Float, by0: Float, by1: Float
    ): Boolean {
        var t0 = 0f
        var t1 = 1f
        val dx = x1 - x0
        val dy = y1 - y0
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x0 - bx0, bx1 - x0, y0 - by0, by1 - y0)
        for (k in 0..3) {
            if (p[k] == 0f) {
                if (q[k] < 0f) return false
            } else {
                val r = q[k] / p[k]
                if (p[k] < 0f) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return t0 <= t1
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
                    // 先校验牌型合法性：不合法弹出红色提示框并给音效/振动反馈，桌面出牌与选择保持不变
                    val group = com.doudizhu.game.logic.CardRuleEngine.identify(selectedCards)
                    if (group.type == CardType.INVALID) {
                        rejectPlay("牌型不合法，请重新选择", 2000)
                        return
                    }
                    // 检查是否能管上家（用规则引擎的正规合法校验，避免炸弹/火箭误判）
                    val lastGroup = gameEngine.stateMachine.lastPlayedGroup
                    val lastPlayer = gameEngine.stateMachine.lastPlayedPlayerIndex
                    if (lastGroup != null && lastPlayer != 0) {
                        if (!com.doudizhu.game.logic.CardRuleEngine.isValidPlay(group, lastGroup)) {
                            rejectPlay("打不过上家的牌，请重新选择", 2000)
                            return
                        }
                    }
                    // 合法出牌：不再预先清空桌面——setTablePlayedCards 会捕获上一手牌做退场动画，
                    // 随后 humanPlay 触发的 onPlayerPlay 回调再展示本手出牌
                    playCardSound()
                    hapticActionConfirm()
                    gameEngine.humanPlay(selectedCards)
                } else {
                    rejectPlay("请先选择要出的牌", 1500)
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
                    sfx { it.confirm() }
                } else {
                    rejectPlay("没有能出的牌", 1500)
                }
                refresh()
            }
            action.startsWith("bid_") -> {
                // 中央弹窗（「xx开始叫地主」/「xx成为地主」）展示期间为模态遮挡，忽略叫分点击；
                // 其它时间按钮始终与状态一致，无需额外墙钟保护。
                if (landlordPopupActive()) {
                    refresh()
                    return
                }
                hapticActionConfirm()
                gameEngine.humanBid(action.removePrefix("bid_").toInt())
                sfx { it.confirm() }
            }
            action == "restart" -> {
                clearAllPlayedCards()
                // 重开新局：立即作废在途的「xx成为地主」弹窗（若此刻正处于弹窗 1.5s 等待期）
                landlordPopupText = ""
                onLandlordPopupDismiss = null
                // 同步取消弹窗关闭定时器，避免遗留的 pending Runnable 在后台仍触发（虽因 text 已空而 no-op）
                landlordPopupCloseRunnable?.let { mainHandler.removeCallbacks(it) }
                landlordPopupCloseRunnable = null
                // 一并清理可能残留的错误提示，避免重开局后旧红字短暂残留
                errorCloseRunnable?.let { mainHandler.removeCallbacks(it) }
                errorCloseRunnable = null
                errorText = ""
                gameOverAnimStart = 0L   // 重置结算横幅时间轴，防止新一局 GAME_OVER 前误画残留横幅
                hapticActionConfirm()
                gameEngine.startNewGame()
            }
            action == "start_normal" -> startGameAt(Difficulty.NORMAL)
            action == "start_master" -> startGameAt(Difficulty.MASTER)
            action == "to_menu" -> {
                clearAllPlayedCards()
                // 返回主界面：同样作废在途的地主弹窗，防止旧回调串场触发新对局状态切换
                landlordPopupText = ""
                onLandlordPopupDismiss = null
                // 同步取消弹窗关闭定时器（同 restart）
                landlordPopupCloseRunnable?.let { mainHandler.removeCallbacks(it) }
                landlordPopupCloseRunnable = null
                // 一并清理可能残留的错误提示
                errorCloseRunnable?.let { mainHandler.removeCallbacks(it) }
                errorCloseRunnable = null
                errorText = ""
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
        animTime = System.currentTimeMillis()
        val pendingButtons = mutableListOf<ButtonRect>()

        // 每帧重新计算手牌间距
        recalcSizes()

        drawBackground(canvas)
        drawTopBar(canvas)
        drawBottomCards(canvas)

        // 横屏布局：AI在左右两侧（主界面不显示电脑A/电脑B信息框）
        if (gameEngine.stateMachine.phase != GamePhase.MENU) {
            drawAIPlayer(canvas, 1, screenWidth - 260f * uiScale, 100f)   // 右侧AI
            drawAIPlayer(canvas, 2, 260f * uiScale, 100f)                  // 左侧AI
        }

        // 桌面中央展示所有玩家出的牌
        drawTablePlayedCards(canvas)

        // 电脑思考中提示（画在出牌区之后，避免被中央牌面遮挡）
        if (gameEngine.stateMachine.phase != GamePhase.MENU) {
            drawAiTurnTip(canvas, 1, screenWidth - 260f * uiScale, 100f)
            drawAiTurnTip(canvas, 2, 260f * uiScale, 100f)
        }

        // 叫分阶段：在两台电脑玩家下方分别显示其叫分情况（开始发牌时清除）
        drawAiBidTips(canvas)

        // 底部人类手牌
        drawHumanHand(canvas)

        // 当前回合高亮提示
        drawTurnHighlight(canvas)

        // 按钮
        drawButtons(canvas, pendingButtons)

        // 轮到你出牌提示（出牌按钮下方、手牌上方）
        drawHumanTurnTip(canvas)

        // 错误提示（红色弹窗，非法操作时弹出）
        drawError(canvas)

        // 「xx成为地主」中央弹窗（叫分全部完成后弹出，保持 1.5s；绘制在按钮之上确保居中显眼）
        drawLandlordPopup(canvas)

        // 计分显示
        drawStats(canvas)

        // 结算时显示所有玩家剩余牌（正面）
        if (gameEngine.stateMachine.phase == GamePhase.GAME_OVER || 
            gameEngine.stateMachine.phase == GamePhase.SETTLING) {
            drawAllRemainingCards(canvas)
        }

        // 结算横幅：最后绘制置于最顶层（滑入 → 停留3秒 → 自动淡出），
        // 位于屏幕上方区域，不遮挡底部剩余手牌与操作按钮
        if (gameEngine.stateMachine.phase == GamePhase.GAME_OVER ||
            gameEngine.stateMachine.phase == GamePhase.SETTLING) {
            drawResultBanner(canvas)
        }

        // 全部绘制完成后原子发布按钮列表，触摸线程永远不会读到半成品
        currentButtons = pendingButtons
    }

    private fun drawBackground(canvas: Canvas) {
        val key = "${screenWidth}x${screenHeight}"
        if (bgGradient == null || bgGradientW != screenWidth.toFloat() || bgGradientH != screenHeight.toFloat()) {
            // 三段式绿呢渐变：顶部深 → 中部亮 → 底部沉，模拟灯光落在桌心
            bgGradient = LinearGradient(
                0f, 0f, 0f, screenHeight.toFloat(),
                intArrayOf(
                    Color.parseColor("#0A3110"),
                    Color.parseColor("#175C24"),
                    Color.parseColor("#0C3A12")
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            bgGradientW = screenWidth.toFloat()
            bgGradientH = screenHeight.toFloat()

            // 桌心柔光（径向渐变，缓存复用）
            // 半径必须 ≥ 光晕中心到屏幕最远角的距离：否则 CLAMP 外圈恒 alpha 带
            // 被屏幕裁切成左右/上下对称的"椭圆弧形色阶"伪影（竖屏下旧值 max(w,h)*0.42
            // 小于该距离，正是伪影成因）。取对角线 ×0.6（= 半对角线 ×1.2），在典型
            // 9:16~9:20 竖屏与横屏下均可覆盖最远角；中心峰值亮度不变，
            // 仅光晕铺展范围变大、边缘更柔和。
            val cx = screenWidth / 2f
            val cy = screenHeight * 0.40f
            val glowRadius = hypot(screenWidth.toFloat(), screenHeight.toFloat()) * 0.6f
            tableGlow = RadialGradient(
                cx, cy, glowRadius,
                intArrayOf(Color.argb(46, 255, 248, 200), Color.argb(14, 255, 248, 200), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            // 暗角（边缘压暗，聚焦桌心；尺寸变化时重建）
            vignette = RadialGradient(
                screenWidth / 2f, screenHeight / 2f,
                hypot(screenWidth.toFloat(), screenHeight.toFloat()) / 2f,
                intArrayOf(Color.TRANSPARENT, Color.argb(28, 0, 0, 0), Color.argb(96, 0, 0, 0)),
                floatArrayOf(0.5f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            vignetteKey = key
        }
        bgPaint.shader = bgGradient
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), bgPaint)
        bgPaint.shader = null

        // 桌心柔光晕
        tableGlow?.let {
            ovalPaint.shader = it
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), ovalPaint)
            ovalPaint.shader = null
        }

        // 桌面中央椭圆装饰（描边 + 淡填充，像赌桌压线）
        val cx = screenWidth / 2f
        val cy = screenHeight * 0.40f
        val oval = RectF(cx - screenWidth * 0.30f, cy - screenHeight * 0.20f,
                         cx + screenWidth * 0.30f, cy + screenHeight * 0.20f)
        ovalPaint.color = Color.argb(16, 255, 236, 179)
        canvas.drawOval(oval, ovalPaint)
        cardBorderPaint.color = Palette.tableOvalStroke
        cardBorderPaint.strokeWidth = 3f
        canvas.drawOval(oval, cardBorderPaint)

        // 四周暗角（在牌面之下、桌面之上，增强纵深与灯光聚焦感）
        if (vignetteKey != key) {
            vignette = RadialGradient(
                screenWidth / 2f, screenHeight / 2f,
                hypot(screenWidth.toFloat(), screenHeight.toFloat()) / 2f,
                intArrayOf(Color.TRANSPARENT, Color.argb(28, 0, 0, 0), Color.argb(96, 0, 0, 0)),
                floatArrayOf(0.5f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            vignetteKey = key
        }
        vignette?.let {
            vignettePaint.shader = it
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), vignettePaint)
            vignettePaint.shader = null
        }

        // 绿呢细噪点：平铺位图叠加极淡明暗颗粒，模拟真实绒布质感（一次性生成，每帧仅一次 drawRect）
        clothNoiseBitmap?.let { nb ->
            noisePaint.shader = BitmapShader(nb, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), noisePaint)
            noisePaint.shader = null
        }
    }

    /** 生成绿呢噪点纹理：透明底 + 明/暗颗粒（约 20% 覆盖率），REPEAT 平铺无缝。
     *  颗粒 alpha 在 6~14 间随机抖动，兼作蓝噪 dithering，打散渐变的量化色阶带 */
    private fun buildClothNoise(): Bitmap? {
        return try {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val px = IntArray(size * size)
            val rnd = java.util.Random(20261019L)   // 固定种子：纹理稳定不闪烁
            for (i in px.indices) {
                val r = rnd.nextDouble()
                px[i] = when {
                    // 亮粒（受光绒毛）：alpha 6~14 随机
                    r < 0.10 -> Color.argb(6 + rnd.nextInt(9), 255, 255, 240)
                    // 暗粒（绒毛阴影）：alpha 6~14 随机
                    r < 0.20 -> Color.argb(6 + rnd.nextInt(9), 0, 0, 0)
                    else -> Color.TRANSPARENT
                }
            }
            bmp.setPixels(px, 0, size, 0, 0, size, size)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    /** 勾股定理求斜边（避免额外 import） */
    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)

    private fun drawTopBar(canvas: Canvas) {
        val barH = 84f * uiScale
        // 顶栏渐变随高度响应式重建（仅尺寸变化时，避免每帧 new LinearGradient）
        if (topBarH != barH) {
            topBarH = barH
            topBarShader = LinearGradient(0f, 0f, 0f, barH * 1.6f, Palette.glassFill, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        topBarPaint.shader = topBarShader
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), barH * 1.6f, topBarPaint)
        topBarPaint.shader = null
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
        val sm = gameEngine.stateMachine
        val isCurrentTurn = sm.currentPlayerIndex == playerIndex && sm.phase == GamePhase.PLAYING

        // 信息面板（放大）
        val panelW = 320f
        val panelH = 180f
        val panelX = centerX - panelW / 2

        // 当前回合：呼吸光晕（正弦波；颜色/透明度每帧变化无法缓存 shader，
        // 但只在"半径+色标"真正变化时才重建对象——量化到 4px/6 alpha 步进，分配降为约 1/8 频率）
        if (isCurrentTurn) {
            val breathe = ((sin(System.currentTimeMillis() / 550.0 * PI) + 1.0) / 2.0).toFloat()   // 0..1
            val glowR = 16f + breathe * 10f
            val quantR = ((glowR / 4f).toInt() * 4f).coerceAtLeast(4f)   // 外扩半径量化（随呼吸真正变化）
            val innerA = ((70 + breathe * 60).toInt() / 6) * 6           // alpha 量化
            // 两个 AI 面板坐标相同，shader 按形状参数缓存即可跨玩家复用；
            // 中心点取面板几何中心（panelW/panelH 为常量），故 key 不含位置信息
            val glowKey = "${quantR.toInt()}_$innerA"
            if (breathGlowKey != glowKey) {
                // Shader 没有公开的可调用的释放方法（native 资源由 Finalizer/WeakReference 自动回收），
                // 直接丢弃旧引用即可，无需（也不能）调用 release()
                breathGlowShader = RadialGradient(
                    panelW / 2f, panelH / 2f, max(panelW * 0.62f, quantR * 2.2f),
                    Color.argb(innerA, 255, 213, 79),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                breathGlowKey = glowKey
            }
            // 用画布平移把"以原点为中心"的缓存渐变对准本面板中心，避免每帧重建 shader
            canvas.save()
            canvas.translate(centerX - panelW / 2f, topY)
            breathGlowPaint.shader = breathGlowShader
            scratchRect.set(-glowR, -glowR, panelW + glowR, panelH + glowR)
            canvas.drawRoundRect(scratchRect, 30f, 30f, breathGlowPaint)
            breathGlowPaint.shader = null
            canvas.restore()
        }

        // 信息面板：柔和投影 + 深色渐变玻璃底（缓存 shader） + 细描边
        aiInfoPaint.color = Palette.glassDrop
        scratchRect.set(panelX + 3f, topY + 6f, panelX + panelW + 3f, topY + panelH + 6f)
        canvas.drawRoundRect(scratchRect, 18f, 18f, aiInfoPaint)
        aiInfoPaint.shader = aiPanelShader
        aiInfoPaint.color = Color.WHITE
        scratchRect.set(panelX, topY, panelX + panelW, topY + panelH)
        canvas.drawRoundRect(scratchRect, 18f, 18f, aiInfoPaint)
        aiInfoPaint.shader = null
        // 面板描边：该玩家本轮已「不出」时用中性灰（提示已行动），否则金色细边
        // 「不出」气泡不自动消退：只要槽位有气泡代号就持续显示，直到他人出牌/过牌或清桌时移除
        val showPassBubble = passSlotEpoch[playerIndex] >= 0
        cardBorderPaint.color = if (showPassBubble) Palette.passBorder
            else Palette.glassBorder
        cardBorderPaint.strokeWidth = 2f
        scratchRect.set(panelX, topY, panelX + panelW, topY + panelH)
        canvas.drawRoundRect(scratchRect, 18f, 18f, cardBorderPaint)

        // 角色（身份：地主/农民）移到顶部，字号用原名称的 50f，颜色保持不变
        val roleText = if (player.role == com.doudizhu.game.model.PlayerRole.LANDLORD) "地主" else "农民"
        textPaint.textSize = 50f
        textPaint.color = if (player.role == com.doudizhu.game.model.PlayerRole.LANDLORD)
            Palette.gold else Palette.textGreen
        canvas.drawText(roleText, centerX, topY + 55f, textPaint)

        // 剩余张数（放大2.5倍: 30*2.5=75 -> 用50f）
        textPaint.textSize = 46f
        textPaint.color = Palette.gold
        canvas.drawText("${player.cardCount}张", centerX, topY + 110f, textPaint)

        // 名称（电脑A/电脑B）移到底部，字号用原角色的 38f，颜色改为灰白色
        textPaint.textSize = 38f
        textPaint.color = Palette.textLightGray
        canvas.drawText(player.name, centerX, topY + 160f, textPaint)

        // 牌背扇形（放大2倍）：结算/结束时由正面剩余牌占据该位置，不再画牌背
        if (sm.phase != GamePhase.SETTLING && sm.phase != GamePhase.GAME_OVER) {
            val count = minOf(player.cardCount, 12)
            val backSpacing = aiBackW * 0.55f
            val totalBackW = backSpacing * (count - 1) + aiBackW
            val backStartX = centerX - totalBackW / 2
            for (i in 0 until count) {
                // 轻微扇形旋转，牌堆更像手持
                val mid = (count - 1) / 2f
                val rot = (i - mid) * 1.6f
                if (rot == 0f) {
                    drawCardBack(canvas, backStartX + i * backSpacing, topY + panelH + 16f, aiBackW, aiBackH)
                } else {
                    canvas.save()
                    canvas.rotate(rot, backStartX + i * backSpacing + aiBackW / 2,
                        topY + panelH + 16f + aiBackH / 2)
                    drawCardBack(canvas, backStartX + i * backSpacing, topY + panelH + 16f, aiBackW, aiBackH)
                    canvas.restore()
                }
            }
        }

        // 「不出」气泡：牌背下方居中，圆角胶囊 + 尾尖指向头像区，淡入动画；
        // 不自动消退：一直显示到其它玩家出牌/过牌（registerPassBubble 清除）或新一手清桌（clearAllPlayedCards）
        // 文字字号与「电脑A/B思考中」一致（44f），胶囊尺寸随文字宽度自适应
        if (showPassBubble) {
            val elapsed = System.currentTimeMillis() - passAnimStart[playerIndex]
            val appear = (elapsed.toFloat() / PASS_BUBBLE_FADEIN_MS).coerceIn(0f, 1f)
            val e = easeOutCubic(appear)
            val label = "不出"
            textPaint.textSize = PASS_BUBBLE_TEXT_SIZE
            val tw = textPaint.measureText(label)
            // 胶囊内边距按字号比例（原 40f 字号：横向 ±28f、纵向 ±13f）等比放大
            val bw = tw + PASS_BUBBLE_TEXT_SIZE * 1.8f
            val bh = PASS_BUBBLE_TEXT_SIZE * 2.35f
            val bx = centerX - bw / 2f
            val by = topY + panelH + 16f + aiBackH + 26f - (1f - e) * 14f
            passBubblePaint.alpha = (200 * e).toInt().coerceIn(0, 255)
            scratchRect.set(bx, by, bx + bw, by + bh)
            canvas.drawRoundRect(scratchRect, bh / 2f, bh / 2f, passBubblePaint)
            // 尾尖小三角（指向上方牌堆）：随气泡放大同比加宽加高，保持比例
            passTrianglePath.reset()
            passTrianglePath.moveTo(centerX - 18f, by + 2f)
            passTrianglePath.lineTo(centerX + 18f, by + 2f)
            passTrianglePath.lineTo(centerX, by - 20f)
            passTrianglePath.close()
            canvas.drawPath(passTrianglePath, passBubblePaint)
            passBubblePaint.alpha = 255
            textPaint.color = Palette.bubbleText
            textPaint.textAlign = Paint.Align.CENTER
            // 文字垂直居中偏移随字号等比缩放（原 40f 字号对应 +14f，即 0.35×字号）
            val textBaselineY = by + bh / 2f + PASS_BUBBLE_TEXT_SIZE * 0.35f
            // 淡入期间文字透明度跟随气泡底漆（drawTextWithShadow 内部会改 color，故此处用 ARGB 直接控制）
            if (e < 1f) {
                val ta = (236 * e).toInt().coerceIn(0, 255)   // 基色 #ECEFF1 各通道约 236+，取整体 alpha
                textPaint.color = Color.argb(ta, 236, 239, 241)
                canvas.drawText(label, centerX, textBaselineY, textPaint)
                textPaint.color = Palette.bubbleText
            } else {
                canvas.drawText(label, centerX, textBaselineY, textPaint)
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
        // 字号统一为 44f（与「不出」气泡文字一致）
        textPaint.textSize = AI_THINKING_TIP_TEXT_SIZE
        // 必须显式设置颜色：textPaint 为共享画笔，上一帧其它绘制（如中央出牌的绿色玩家名）
        // 会残留其颜色；不显式设色时提示文字会偶发变成绿色/白色（bug 修复）
        textPaint.color = Palette.gold
        textPaint.textAlign = Paint.Align.CENTER
        drawTextWithShadow(canvas, thinkingTip(playerIndex), centerX,
            topY + 180f + 16f + aiBackH + 52f, textPaint)
    }

    /**
     * 叫分阶段：在两台电脑玩家的牌背下方分别显示其叫分情况。
     * - 已叫分：显示「叫1分 / 叫2分 / 叫3分」（金色）；不叫：显示「不叫」（灰色）
     * - 尚未行动的玩家：**不显示任何内容**（不再出现「电脑A/B思考中」提示——
     *   叫分节奏由引擎控制：每位结果展示后暂停约 1.5s 才进入下一位，结果上屏即清晰可读）
     * 展示为引擎状态的纯函数：直接读 stateMachine.actedBidPlayers 与 Player.bidScore；
     * 仅在 BIDDING 阶段绘制，阶段切到 PLAYING（地主确定）后自动消失。
     */
    private fun drawAiBidTips(canvas: Canvas) {
        if (!::gameEngine.isInitialized) return
        if (gameEngine.stateMachine.phase != GamePhase.BIDDING) return
        // 与 drawAIPlayer / drawAiTurnTip 的槽位坐标保持一致
        drawAiBidTip(canvas, 1, screenWidth - 260f * uiScale, 100f)
        drawAiBidTip(canvas, 2, 260f * uiScale, 100f)
    }

    private fun drawAiBidTip(canvas: Canvas, playerIndex: Int, centerX: Float, topY: Float) {
        val sm = gameEngine.stateMachine
        if (playerIndex !in sm.actedBidPlayers) return
        val score = gameEngine.players[playerIndex].bidScore.coerceIn(0, 3)
        val label: String
        val color: Int
        if (score > 0) {
            label = "叫" + score + "分"
            color = Palette.gold
        } else {
            label = "不叫"
            color = Color.parseColor("#B0BEC5")
        }
        textPaint.textSize = AI_BID_TIP_TEXT_SIZE * 1.5f
        textPaint.color = color
        textPaint.textAlign = Paint.Align.CENTER
        drawTextWithShadow(canvas, label, centerX,
            topY + 180f + 16f + aiBackH + 52f, textPaint)
    }


    private fun drawHumanTurnTip(canvas: Canvas) {
        if (gameEngine.stateMachine.phase != GamePhase.PLAYING) return
        if (gameEngine.stateMachine.currentPlayerIndex != 0) return
        textPaint.textSize = 44f
        textPaint.color = Palette.gold
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("轮到你出牌", screenWidth / 2f, screenHeight - cardH - 165f, textPaint)
    }

    /** 桌面中央展示所有玩家的出牌（放大） */
    private fun drawTablePlayedCards(canvas: Canvas) {
        val centerY = screenHeight * 0.38f

        // 退场旧牌：先于新手牌绘制（不同槽位，互不遮挡），在其自身坐标处下移 + 淡出
        drawOutgoingCards(canvas)

        // 左侧AI出的牌
        tablePlayedCards[2]?.let { cards ->
            if (cards.isNotEmpty()) {
                drawPlayedCardsAt(canvas, cards, screenWidth * 0.30f, centerY, slot = 2)
                textPaint.textSize = 36f
                textPaint.color = Palette.textGreen
                canvas.drawText("电脑B", screenWidth * 0.30f, centerY - tableCardH / 2 - 20f, textPaint)
            }
        }

        // 人类玩家出的牌
        tablePlayedCards[0]?.let { cards ->
            if (cards.isNotEmpty()) {
                drawPlayedCardsAt(canvas, cards, screenWidth * 0.50f, centerY + 50f, slot = 0)
                textPaint.textSize = 36f
                textPaint.color = Color.parseColor("#FFFFFF")
                canvas.drawText("你", screenWidth * 0.50f, centerY + 50f - tableCardH / 2 - 20f, textPaint)
            }
        }

        // 右侧AI出的牌
        tablePlayedCards[1]?.let { cards ->
            if (cards.isNotEmpty()) {
                drawPlayedCardsAt(canvas, cards, screenWidth * 0.70f, centerY, slot = 1)
                textPaint.textSize = 36f
                textPaint.color = Palette.textGreen
                canvas.drawText("电脑A", screenWidth * 0.70f, centerY - tableCardH / 2 - 20f, textPaint)
            }
        }
    }

    /** 绘制正在退场的上一手牌：在其自身槽位坐标处整体下移并同步淡出（与新手牌入场同周期 380ms）。
     *  由 setTablePlayedCards 在出牌瞬间捕获"桌上正在展示的那一手"触发，覆盖跨槽位的常见过渡场景。 */
    private fun drawOutgoingCards(canvas: Canvas) {
        val cards = outCards
        if (cards == null || outSlot < 0) return
        val op = ((animTime - outAnimStartMs).toFloat() / OUT_ANIM_DUR).coerceIn(0f, 1f)
        if (op >= 1f) { outCards = null; return }   // 动画结束：释放，避免每帧空绘
        val oe = easeOutCubic(op)
        // 退场牌沿用其原槽位的屏幕坐标（与 drawTablePlayedCards 各槽位一致）
        val (cx, cyBase) = when (outSlot) {
            0 -> screenWidth * 0.50f to screenHeight * 0.38f + 50f
            1 -> screenWidth * 0.70f to screenHeight * 0.38f
            else -> screenWidth * 0.30f to screenHeight * 0.38f
        }
        val pTotalW = tableSpacing * (cards.size - 1) + tableCardW
        val pStartX = cx - pTotalW / 2f
        val pStartY = cyBase - tableCardH / 2f + oe * 46f   // 整体下移
        val pAlpha = (1f - oe) * 0.9f                       // 同步淡出
        for ((i, card) in cards.withIndex()) {
            drawMiniCard(canvas, pStartX + i * tableSpacing, pStartY, tableCardW, tableCardH,
                card, faceUp = true, alpha = pAlpha)
        }
    }

    /** 在指定位置绘制一组出牌（带逐张落桌入场动画：从上方轻落 + 淡入） */
    private fun drawPlayedCardsAt(canvas: Canvas, cards: List<Card>, centerX: Float, centerY: Float,
                                  slot: Int = -1) {
        if (cards.isEmpty()) return
        val totalW = tableSpacing * (cards.size - 1) + tableCardW
        val startX = centerX - totalW / 2
        val startY = centerY - tableCardH / 2

        val elapsed = if (slot >= 0) animTime - playAnimStart[slot] else Long.MAX_VALUE

        for ((i, card) in cards.withIndex()) {
            // 入场节奏放慢至 0.6 倍：单张时长 240→400ms，逐张错峰 45→75ms
            val p = ((elapsed - i * 75L).toFloat() / 400f).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val e = easeOutCubic(p)
            val dropY = startY - (1f - e) * 60f
            val alpha = 0.35f + 0.65f * e
            drawMiniCard(canvas, startX + i * tableSpacing, dropY, tableCardW, tableCardH,
                card, faceUp = true, alpha = alpha)
        }
    }

    /** ease-out-cubic：先快后缓的落定曲线 */
    private fun easeOutCubic(t: Float): Float {
        val x = t - 1f
        return 1f + x * x * x
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

            // 选中上移使用平滑缓动（每帧向目标值靠拢，形成连续上浮/回落动画；
            // 缓动系数 0.7 = 原 0.35 的 2 倍速，选中/取消动画加快一倍）
            val liftTarget = if (isSelected) 60f else 0f
            if (i < liftValues.size) {
                val cur = liftValues[i]
                var next = cur + (liftTarget - cur) * 0.7f
                if (abs(next - liftTarget) < 0.5f) next = liftTarget
                liftValues[i] = next
            }
            val lift = if (i < liftValues.size) liftValues[i] else liftTarget

            // 默认位置（含选中上移、微弧偏移）
            var cy = baseY - lift - arcOffset
            var alpha = 1f
            var scale = 1f
            // 选中牌轻微放大
            if (!isHandReveal && lift > 1f) scale = 1f + 0.03f * (lift / 60f)

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

    /** 选中金色光晕 shader 缓存：本地坐标（以牌中心为原点），按量化 alpha 重建，稳态选中（alpha=1）不重建 */
    private fun updateSelectGlowShader(alpha: Float) {
        val qa = (alpha * 20f).toInt().coerceIn(0, 20)
        if (selectGlowKey != qa.toString()) {
            val a110 = (110 * alpha).toInt().coerceIn(0, 255)
            val a40 = (40 * alpha).toInt().coerceIn(0, 255)
            selectGlowShader = RadialGradient(
                cardW / 2f, cardH / 2f, max(cardW, cardH) * 0.75f,
                intArrayOf(Color.argb(a110, 255, 214, 64),
                           Color.argb(a40, 255, 214, 64), Color.TRANSPARENT),
                floatArrayOf(0.62f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            selectGlowKey = qa.toString()
        }
    }

    /** 绘制单张扑克牌（手牌，大尺寸）；alpha<1 时用于开局淡入，绘制后还原共享画笔的 alpha */
    private fun drawCard(canvas: Canvas, x: Float, y: Float, card: Card,
                         selected: Boolean, rotation: Float = 0f, alpha: Float = 1f) {
        val fade = alpha < 0.999f
        val a = if (fade) (alpha * 255f).toInt().coerceIn(0, 255) else 255
        // 选中金色光晕（牌之下、未旋转；缓存本地坐标 shader + 平移到牌位，避免每帧 new RadialGradient）
        if (selected) {
            updateSelectGlowShader(alpha)
            canvas.save()
            canvas.translate(x, y)
            selectGlowPaint.shader = selectGlowShader
            scratchRect.set(-12f, -12f, cardW + 12f, cardH + 12f)
            canvas.drawRoundRect(scratchRect, cardRadius + 10f, cardRadius + 10f, selectGlowPaint)
            selectGlowPaint.shader = null
            canvas.restore()
        }

        canvas.save()
        canvas.rotate(rotation, x + cardW / 2, y + cardH / 2)

        // 投影：始终有一层柔和底影，选中时加深加高，营造悬浮感
        if (fade) shadowPaint.alpha = a
        shadowPaint.color = if (selected) Color.argb(90, 0, 0, 0) else Color.argb(45, 0, 0, 0)
        val shOff = if (selected) 10f else 6f
        canvas.drawRoundRect(x + 4f, y + shOff, x + cardW + 4f, y + cardH + shOff,
            cardRadius, cardRadius, shadowPaint)
        if (fade) shadowPaint.alpha = 255


        // 牌面：象牙白→冷白微渐变（更有纸质厚度感）
        cardPaint.shader = cardFaceShader
        cardPaint.color = Color.WHITE
        if (fade) cardPaint.alpha = a
        canvas.drawRoundRect(x, y, x + cardW, y + cardH, cardRadius, cardRadius, cardPaint)
        cardPaint.shader = null
        if (fade) cardPaint.alpha = 255

        // 边框：选中金框，普通浅灰细框
        cardBorderPaint.color = if (selected) Palette.cardBorderSelected else Palette.cardBorder
        cardBorderPaint.strokeWidth = if (selected) 5f else 2.5f
        if (fade) cardBorderPaint.alpha = a
        canvas.drawRoundRect(x, y, x + cardW, y + cardH, cardRadius, cardRadius, cardBorderPaint)
        if (fade) cardBorderPaint.alpha = 255

        val textColor = if (card.isRed) Palette.rankRed else Palette.rankBlack

        // 左上角花色+点数（花色用普通字体画笔：粗体字库缺 ♠♥♣♦ 字形时会渲染成空白）
        suitSymbolPaint.textSize = cardW * 0.15f
        suitSymbolPaint.color = textColor
        suitSymbolPaint.textAlign = Paint.Align.LEFT
        if (fade) suitSymbolPaint.alpha = a
        canvas.drawText(card.suitSymbol, x + 14f, y + cardH * 0.18f, suitSymbolPaint)
        smallTextPaint.textSize = cardW * 0.36f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.LEFT
        if (fade) smallTextPaint.alpha = a
        canvas.drawText(card.displayText, x + 14f, y + cardH * 0.36f, smallTextPaint)
        if (fade) smallTextPaint.alpha = 255

        // 中央大牌值字点数（主视觉：以大字点数取代原来的大花色符号；「小王/大王」显示「小/大」）
        // 注意必须用普通字体画笔 smallTextPaint：此处复用左上角索引时其 textAlign 为 LEFT，需显式置 CENTER
        smallTextPaint.textSize = cardW * 0.52f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.CENTER
        if (fade) smallTextPaint.alpha = a
        canvas.drawText(card.displayText, x + cardW / 2, y + cardH * 0.68f, smallTextPaint)
        if (fade) smallTextPaint.alpha = 255
        smallTextPaint.textAlign = Paint.Align.LEFT

        // 右下角点数（旋转 180° 绘制，模拟真实纸牌的对角镜像索引）
        canvas.save()
        canvas.rotate(180f, x + cardW - 14f, y + cardH - 34f)
        smallTextPaint.textSize = cardW * 0.24f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.LEFT
        if (fade) smallTextPaint.alpha = a
        canvas.drawText(card.displayText, x + cardW - 34f, y + cardH - 30f, smallTextPaint)
        if (fade) smallTextPaint.alpha = 255
        canvas.restore()

        // 右下角小花色（紧贴点数下方）
        suitSymbolPaint.textSize = cardW * 0.13f
        suitSymbolPaint.color = textColor
        suitSymbolPaint.textAlign = Paint.Align.RIGHT
        if (fade) suitSymbolPaint.alpha = a
        canvas.drawText(card.suitSymbol, x + cardW - 14f, y + cardH - 20f, suitSymbolPaint)
        if (fade) suitSymbolPaint.alpha = 255

        canvas.restore()
    }

    /** 绘制小尺寸牌（桌面展示/底牌）；alpha 用于入场淡入 */
    private fun drawMiniCard(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                             card: Card, faceUp: Boolean, alpha: Float = 1f) {
        if (!faceUp) { drawCardBack(canvas, x, y, w, h); return }
        val fade = alpha < 0.999f
        val a = if (fade) (alpha * 255f).toInt().coerceIn(0, 255) else 255

        val r = 10f
        // 轻投影
        shadowPaint.color = Color.argb((36 * alpha).toInt(), 0, 0, 0)
        canvas.drawRoundRect(x + 2f, y + 4f, x + w + 2f, y + h + 4f, r, r, shadowPaint)
        cardPaint.shader = cardFaceShader
        cardPaint.color = Color.WHITE
        if (fade) cardPaint.alpha = a
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardPaint)
        cardPaint.shader = null
        cardBorderPaint.color = Palette.cardBorder
        cardBorderPaint.strokeWidth = 2f
        if (fade) cardBorderPaint.alpha = a
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardBorderPaint)
        if (fade) { cardPaint.alpha = 255; cardBorderPaint.alpha = 255 }

        val textColor = if (card.isRed) Palette.rankRed else Palette.rankBlack

        // 左上角：花色 + 点数（与手牌 drawCard 布局风格一致）
        // 花色必须用普通字体画笔 suitSymbolPaint：粗体字库常缺 ♠♥♣♦ 字形，用 smallTextPaint(粗体) 会渲染成空白
        suitSymbolPaint.textSize = w * 0.30f
        suitSymbolPaint.color = textColor
        suitSymbolPaint.textAlign = Paint.Align.LEFT
        if (fade) suitSymbolPaint.alpha = a
        canvas.drawText(card.suitSymbol, x + 6f, y + h * 0.22f, suitSymbolPaint)
        smallTextPaint.textSize = w * 0.40f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.LEFT
        if (fade) smallTextPaint.alpha = a
        canvas.drawText(card.displayText, x + 6f, y + h * 0.50f, smallTextPaint)

        // 中央大牌值字点数（主视觉，与手牌样式统一：以大字点数取代原来的大花色符号）
        smallTextPaint.textSize = w * 0.58f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.CENTER
        if (fade) smallTextPaint.alpha = a
        canvas.drawText(card.displayText, x + w / 2, y + h * 0.86f, smallTextPaint)
        if (fade) { smallTextPaint.alpha = 255 }
        // 复位对齐方式：smallTextPaint 为共享画笔，下游（牌面索引/文字）默认按 LEFT 使用
        smallTextPaint.textAlign = Paint.Align.LEFT
    }

    /** 绘制牌背：白切边 + 蓝色渐变主体 + 内描线 + 中心菱形徽记 */
    private fun drawCardBack(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = 8f
        // 外白边（真实纸牌的切边感）
        cardPaint.shader = null
        cardPaint.color = Color.parseColor("#E8EDF3")
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, cardPaint)
        // 蓝色渐变主体
        cardPaint.shader = cardBackShader
        cardPaint.color = Color.WHITE
        val inset = (w * 0.045f).coerceAtLeast(2f)
        canvas.drawRoundRect(x + inset, y + inset, x + w - inset, y + h - inset, r - 2f, r - 2f, cardPaint)
        cardPaint.shader = null
        // 内侧浅色描线
        cardBorderPaint.color = Color.argb(120, 255, 255, 255)
        cardBorderPaint.strokeWidth = 1.5f
        val m = w * 0.14f
        canvas.drawRoundRect(x + m, y + m, x + w - m, y + h - m, r - 3f, r - 3f, cardBorderPaint)
        // 中心菱形徽记
        val cx = x + w / 2
        val cy = y + h / 2
        val dr = min(w, h) * 0.16f
        cardPaint.color = Color.argb(90, 255, 236, 179)
        canvas.save()
        canvas.rotate(45f, cx, cy)
        canvas.drawRect(cx - dr, cy - dr, cx + dr, cy + dr, cardPaint)
        cardPaint.color = Color.argb(140, 255, 236, 179)
        val dr2 = dr * 0.55f
        canvas.drawRect(cx - dr2, cy - dr2, cx + dr2, cy + dr2, cardPaint)
        canvas.restore()
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
        textPaint.color = Palette.textGreen
        textPaint.textAlign = if (leftAlign) Paint.Align.LEFT else Paint.Align.RIGHT
        canvas.drawText(label, if (leftAlign) bandLeft else bandRight, backY - 10f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER   // 还原默认对齐，避免影响其它绘制

        for ((i, card) in cards.withIndex()) {
            drawMiniCard(canvas, startX + i * pitch, backY, miniW, miniH, card, faceUp = true)
        }
    }

    // ==================== 当前回合高亮提示 ====================

    /** 某 AI 玩家信息区真实包围盒（信息面板 + 牌背扇形 + 可能的「不出」气泡），
     *  用于让「轮到出牌」高亮框精确包住并略大于该信息区域。
     *  坐标与 drawAIPlayer 严格一致：中心 x 在左右边缘各内缩 260f*uiScale、顶 y=100f、
     *  面板固定 320x180；牌背宽随 uiScale(aiBackW/aiBackH) 变化，故框也随之缩放。 */
    private fun aiInfoBounds(playerIndex: Int): RectF {
        val centerX = if (playerIndex == 1) screenWidth - 260f * uiScale else 260f * uiScale
        val topY = 100f
        val panelW = 320f
        val panelH = 180f
        val p = gameEngine.players[playerIndex]
        val count = maxOf(minOf(p.cardCount, 12), 1)
        val backSpacing = aiBackW * 0.55f
        val totalBackW = backSpacing * (count - 1) + aiBackW
        val halfW = max(panelW / 2f, totalBackW / 2f)
        // 「不出」气泡仅在该玩家已（在本轮）过牌时显示，按需把框下沿一并包住
        val passBh = if (passSlotEpoch[playerIndex] >= 0) PASS_BUBBLE_TEXT_SIZE * 2.35f else 0f
        val padX = 18f
        val padY = 16f
        val left = centerX - halfW - padX
        val right = centerX + halfW + padX
        val top = topY - padY
        val bottom = topY + panelH + 16f + aiBackH + 26f + passBh + padY
        return RectF(left, top, right, bottom)
    }

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
                // 右侧AI高亮：框严格包裹其信息区（随 uiScale 缩放）
                val pulseAlpha = ((abs(highlightFrame - 30) / 30.0f) * 120 + 60).toInt()
                highlightPaint.color = Color.argb(pulseAlpha, 255, 152, 0)
                highlightPaint.strokeWidth = 6f
                canvas.drawRoundRect(aiInfoBounds(1), 20f, 20f, highlightPaint)
            }
            2 -> {
                // 左侧AI高亮：框严格包裹其信息区（随 uiScale 缩放）
                val pulseAlpha = ((abs(highlightFrame - 30) / 30.0f) * 120 + 60).toInt()
                highlightPaint.color = Color.argb(pulseAlpha, 255, 152, 0)
                highlightPaint.strokeWidth = 6f
                canvas.drawRoundRect(aiInfoBounds(2), 20f, 20f, highlightPaint)
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
                    val masterColor = Palette.rankRed
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
                // 仅在中央弹窗（「xx开始叫地主」/「xx成为地主」）未展示、且人类尚未在本轮叫分时，
                // 才显示/注册叫分按钮；弹窗遮挡或已叫分（如刚叫 3 分、引擎保留 1.5s 再弹地主）时隐藏，避免重复点击。
                if (!landlordPopupActive() && 0 !in gameEngine.stateMachine.actedBidPlayers) {
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
                        2 to Color.parseColor("#F57C00"), 3 to Palette.rankRed
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
            // 游戏结束：操作按钮（结算横幅移至 drawGame 最后绘制，保证置于最顶层）
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

    /**
     * 结算横幅尺寸/字号常量：文字放大后统一由此派生（横幅高度、纵向渐变、各行基线），
     * 调整字号或行距时只需改这里，避免出现「字大框小」或渐变与底框错位。
     */
    private val BANNER_TITLE_TEXT = 62f          // 胜负标题字号
    private val BANNER_SCORE_TEXT = 56f          // 本局得分变化 + 倍数说明字号（与放大后的角色说明一致）
    private val BANNER_SUB_TEXT = 56f            // 获胜方角色说明字号（原 28f，按要求放大一倍）
    private val BANNER_TITLE_BASE_Y = 84f        // 标题基线（相对横幅顶部）
    private val BANNER_SCORE_BASE_Y = 158f       // 得分行基线
    private val BANNER_SUB_BASE_Y = 226f         // 角色说明基线
    private val BANNER_SUB_ONLY_BASE_Y = 176f    // 无得分行时角色说明基线（上移补位）
    private val BANNER_HEIGHT = 258f             // 横幅高度（须与渐变终止坐标一致）

    /** 结算横幅纵向渐变缓存：入场动画期间 by 每帧变化，用画布平移对准位置复用，避免逐帧 new */
    private val bannerGradWin = LinearGradient(
        0f, 0f, 0f, BANNER_HEIGHT,
        Color.parseColor("#FFD54F"), Color.parseColor("#F57F17"), Shader.TileMode.CLAMP
    )
    private val bannerGradLose = LinearGradient(
        0f, 0f, 0f, BANNER_HEIGHT,
        Color.parseColor("#90A4AE"), Color.parseColor("#455A64"), Shader.TileMode.CLAMP
    )

    /**
     * 结算横幅：胜负标题 + 本局积分结算信息（原独立消息提示框内容并入此处）+ 金色/灰色圆角底。
     * 时间轴：滑入淡入 → 停留 3 秒 → 自动淡出；由 drawGame 最后绘制，置于最顶层不遮挡按钮之外的内容。
     */
    private fun drawResultBanner(canvas: Canvas) {
        val sm = gameEngine.stateMachine
        if (sm.landlordIndex < 0) return
        val humanIsLandlord = sm.landlordIndex == 0
        val won = lastRoundHumanWin
        val title = if (won) "胜利！" else "惜败…"
        // 副题：获胜方角色说明（整合原提示框文案的角色部分）
        val sub = when {
            won && humanIsLandlord -> "地主获胜，积分入账"
            won -> "农民获胜，配合漂亮"
            humanIsLandlord -> "地主落败，再接再厉"
            else -> "农民失利，下把翻盘"
        }
        // 第三行：本局得分变化 + 倍数说明（合并自原 showMessage 的结算积分提示）
        // scoreDelta=0 视为未提供积分信息：隐藏该行并压缩布局，避免显示误导性的「本局 +0 分」
        val hasScore = lastRoundScoreDelta != 0
        val sign = if (lastRoundScoreDelta >= 0) "+" else "-"
        val scoreLine = "本局 $sign${abs(lastRoundScoreDelta)} 分" +
            if (lastRoundMultiplierText.isNotEmpty()) "（${lastRoundMultiplierText}）" else ""

        val elapsed = System.currentTimeMillis() - gameOverAnimStart
        // 时间轴分段：滑入(0~450ms) → 停留(至 3450ms) → 淡出(至 4050ms)
        val p = (elapsed.toFloat() / BANNER_SLIDE_MS).coerceIn(0f, 1f)
        val e = easeOutCubic(p)
        val fade = if (elapsed <= BANNER_SLIDE_MS + BANNER_HOLD_MS) 1f
            else 1f - ((elapsed - BANNER_SLIDE_MS - BANNER_HOLD_MS).toFloat() / BANNER_FADE_MS).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val appear = e * fade
        // 文字整体透明度跟随入场/淡出（alpha=255 时与不设置完全等价，正常态零开销）
        val textAlpha = (255 * appear).toInt().coerceIn(0, 255)

        // 字号放大后横幅加宽：保证「本局 +N 分（倍数说明）」一行在最小屏上也能容纳
        val bw = min(screenWidth * 0.78f, 1040f)
        val bh = BANNER_HEIGHT
        val bx = (screenWidth - bw) / 2f
        val by = screenHeight * 0.16f - (1f - e) * 60f

        // 平移到横幅位置后使用固定尺寸渐变（不随 by 逐帧重建 shader）
        canvas.save()
        canvas.translate(bx, by)
        bannerPaint.shader = if (won) bannerGradWin else bannerGradLose
        bannerPaint.alpha = (235 * appear).toInt().coerceIn(0, 255)
        scratchRect.set(0f, 0f, bw, bh)
        canvas.drawRoundRect(scratchRect, 26f, 26f, bannerPaint)
        bannerPaint.shader = null
        bannerStrokePaint.alpha = (160 * appear).toInt().coerceIn(0, 255)
        scratchRect.set(0f, 0f, bw, bh)
        canvas.drawRoundRect(scratchRect, 26f, 26f, bannerStrokePaint)
        bannerPaint.alpha = 255
        canvas.restore()

        textPaint.textAlign = Paint.Align.CENTER   // 共享画笔默认 CENTER，显式声明防上游残留 LEFT
        textPaint.textSize = BANNER_TITLE_TEXT
        textPaint.color = withAlpha(if (won) Color.parseColor("#FFFFFF") else Palette.bubbleText, textAlpha)
        drawTextWithShadow(canvas, title, screenWidth / 2f, by + BANNER_TITLE_BASE_Y, textPaint)
        // hasScore=false（未提供积分）时不画得分行，副题上移补位，避免横幅中部留大片空白
        if (hasScore) {
            textPaint.textSize = BANNER_SCORE_TEXT
            textPaint.color = withAlpha(if (won) Color.parseColor("#FFF8E1") else Palette.bubbleText, textAlpha)
            canvas.drawText(scoreLine, screenWidth / 2f, by + BANNER_SCORE_BASE_Y, textPaint)
            textPaint.textSize = BANNER_SUB_TEXT
            textPaint.color = withAlpha(if (won) Color.parseColor("#FFECB3") else Color.parseColor("#CFD8DC"), textAlpha)
            canvas.drawText(sub, screenWidth / 2f, by + BANNER_SUB_BASE_Y, textPaint)
        } else {
            textPaint.textSize = BANNER_SUB_TEXT
            textPaint.color = withAlpha(if (won) Color.parseColor("#FFF8E1") else Palette.bubbleText, textAlpha)
            canvas.drawText(sub, screenWidth / 2f, by + BANNER_SUB_ONLY_BASE_Y, textPaint)
        }
        // textPaint 为共享画笔且默认 CENTER：保持 CENTER，避免影响 drawStats 等下游绘制
    }

    /** 替换颜色的 alpha 通道（供结算横幅文字跟随入场/淡出统一透明） */
    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    @Suppress("UNUSED_PARAMETER") // color 保留在签名中仅为与 drawTextWithShadow 等绘制辅助函数保持一致的调用约定
    private fun addButton(canvas: Canvas, target: MutableList<ButtonRect>, text: String, x: Float, y: Float,
                          w: Float, h: Float, color: Int, pressedColor: Int, action: String) {
        val rect = RectF(x, y, x + w, y + h)
        target.add(ButtonRect(text, rect, color, pressedColor, action))

        // 判断是否被按下（按动作匹配，按钮列表重建也不会错位）
        val isPressed = action == pressedAction
        // 柔和投影：抬起悬浮、按下贴桌
        shadowPaint.color = Color.argb(if (isPressed) 60 else 100, 0, 0, 0)
        val shOff = if (isPressed) 3f else 7f
        scratchRect.set(x + 3f, y + shOff, x + w + 3f, y + h + shOff)
        canvas.drawRoundRect(scratchRect, 22f, 22f, shadowPaint)
        // 主体：纵向渐变（亮面→底色），比纯色更立体
        buttonPaint.shader = LinearGradient(
            0f, y, 0f, y + h,
            lighten(color, 0.20f), if (isPressed) pressedColor else darken(color, 0.10f),
            Shader.TileMode.CLAMP
        )
        buttonPaint.color = Color.WHITE
        canvas.drawRoundRect(rect, 22f, 22f, buttonPaint)
        buttonPaint.shader = null

        // 按下时添加深色覆盖层
        if (isPressed) {
            canvas.drawRoundRect(rect, 22f, 22f, buttonOverlayPaint)
        }

        // 按钮顶部高光弧带 + 细描边（玻璃边缘感）
        scratchRect.set(x + 3f, y + 2f, x + w - 3f, y + h * 0.48f)
        canvas.drawRoundRect(scratchRect, 22f, 22f, buttonHighlightPaint)
        canvas.drawRoundRect(rect, 22f, 22f, buttonStrokePaint)

        // 文字颜色固定白色：buttonTextPaint 被设置窗口等复用且会被改成灰色，
        // 若此处不显式复位，退出设置后游戏内按钮会继承灰色（叫分/提示/出牌变灰）。
        buttonTextPaint.color = Color.WHITE
        buttonTextPaint.textSize = 56f
        drawTextWithShadow(canvas, text, x + w / 2, y + h / 2 + 18f, buttonTextPaint)
    }

    /** 颜色提亮（factor 0..1，向白靠拢） */
    private fun lighten(color: Int, f: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        fun ch(c: Int) = min(255, max(0, (c + (255 - c) * f).toInt()))
        return Color.argb(Color.alpha(color), ch(r), ch(g), ch(b))
    }

    /** 颜色压暗（factor 0..1，向黑靠拢） */
    private fun darken(color: Int, f: Float): Int {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        fun ch(c: Int) = min(255, max(0, (c * (1f - f)).toInt()))
        return Color.argb(Color.alpha(color), ch(r), ch(g), ch(b))
    }

    /** 带柔和投影的文字（提升深底文字可读性与层次）；调用方负责 paint 的 textSize/color/align */
    private fun drawTextWithShadow(canvas: Canvas, text: String, cx: Float, baseY: Float,
                                   paint: Paint) {
        val saved = paint.color
        paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawText(text, cx + 2f, baseY + 3f, paint)
        paint.color = saved
        canvas.drawText(text, cx, baseY, paint)
    }

    /** 思考中动态省略号：随时间循环 . .. ... */
    private fun thinkingTip(playerIndex: Int): String {
        val dots = ".".repeat(((animTime / 400L) % 3L).toInt() + 1)
        return (if (playerIndex == 1) "电脑A思考中" else "电脑B思考中") + dots
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
        shadowPaint.color = Color.argb(if (isPressed) 60 else 100, 0, 0, 0)
        val shOff = if (isPressed) 3f else 7f
        scratchRect.set(x + 3f, y + shOff, x + w + 3f, y + h + shOff)
        canvas.drawRoundRect(scratchRect, 22f, 22f, shadowPaint)
        buttonPaint.shader = LinearGradient(
            0f, y, 0f, y + h,
            lighten(color, 0.20f), if (isPressed) pressedColor else darken(color, 0.10f),
            Shader.TileMode.CLAMP
        )
        buttonPaint.color = Color.WHITE
        canvas.drawRoundRect(rect, 22f, 22f, buttonPaint)
        buttonPaint.shader = null
        if (isPressed) canvas.drawRoundRect(rect, 22f, 22f, buttonOverlayPaint)
        scratchRect.set(x + 3f, y + 2f, x + w - 3f, y + h * 0.48f)
        canvas.drawRoundRect(scratchRect, 22f, 22f, buttonHighlightPaint)
        canvas.drawRoundRect(rect, 22f, 22f, buttonStrokePaint)

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
            scratchRect.set(-toothW / 2, -r * 0.95f, toothW / 2, -r * 0.62f)
            canvas.drawRoundRect(scratchRect, 3f, 3f, gearPaint)
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
            Palette.rankRed, Color.parseColor("#B71C1C"), "reset_master")
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
            Color.parseColor("#546E7A"), Palette.bubbleFill, "master_think_dec")
        addButton(canvas, target, "+", sbx + sbw + stepGap, sby, sbw, 80f,
            Color.parseColor("#546E7A"), Palette.bubbleFill, "master_think_inc")
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
        val leftPressed = if (leftSelected) Color.parseColor("#1B5E20") else Palette.bubbleFill
        val rightColor = if (rightSelected) Color.parseColor("#388E3C") else Color.parseColor("#546E7A")
        val rightPressed = if (rightSelected) Color.parseColor("#1B5E20") else Palette.bubbleFill
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
            Palette.rankRed, Color.parseColor("#B71C1C"), "confirm_reset")
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

    /** 错误提示（红色弹窗，非法操作时弹出；到期自动清除）。
     *  注意：清除时必须再触发一次重绘——非 PLAYING 阶段（叫分/结算）画面静止时绘制线程会停帧，
     *  若仅在此帧 return 而不 refresh，弹窗文字将残留在最后一帧画面上直到下一次刷新才消失。
     *  （PLAYING 阶段本就逐帧重绘，此处多发的一次 refresh 会被立即消费，无额外开销） */
    private fun drawError(canvas: Canvas) {
        if (errorText.isEmpty()) return
        // 兜底：超时由 closeError 定时器主动清除，此处仅在绘制帧先于定时器到达时做双保险
        if (System.currentTimeMillis() > errorTimer) { closeError(); return }

        textPaint.textSize = 48f
        textPaint.color = Palette.errText
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
        textPaint.color = Palette.modeRed      // 红字
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

}
