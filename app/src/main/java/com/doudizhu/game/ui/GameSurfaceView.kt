package com.doudizhu.game.ui

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.doudizhu.game.logic.GameEngine
import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardType
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
    /** 手牌间距（动态计算，填满底部90%） */
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
        // 动态计算手牌间距，填满底部90%宽度
        val hand = if (::gameEngine.isInitialized) gameEngine.players[0].handCards else emptyList()
        if (hand.size > 1) {
            val availableWidth = screenWidth * 0.90f
            handSpacing = ((availableWidth - cardW) / (hand.size - 1)).coerceIn(cardW * 0.35f, cardW * 0.85f)
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
    private val msgBgPaint = Paint().apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
    private val errBgPaint = Paint().apply { color = Color.parseColor("#DD000000"); style = Paint.Style.FILL }
    private val aiHighlightPaint = Paint().apply {
        color = Color.argb(120, 255, 152, 0); style = Paint.Style.FILL
    }
    /** 背景渐变缓存（仅尺寸变化时重建） */
    private var bgGradient: LinearGradient? = null
    private var bgGradientW = 0f
    private var bgGradientH = 0f

    /** 按钮区域（CopyOnWrite：绘制线程每帧重建，触摸线程并发读取安全） */
    data class ButtonRect(val text: String, val rect: RectF, val color: Int, val pressedColor: Int, val action: String)
    private val buttons = java.util.concurrent.CopyOnWriteArrayList<ButtonRect>()
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

    /** 计分系统 */
    private var totalScore = 0

    /** 当前回合高亮动画帧 */
    private var highlightFrame = 0

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
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
            }
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {}
    }

    /** 播放简单音效 */
    private fun playTone(toneType: Int, durationMs: Int = 100) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (_: Exception) {}
    }

    /** 播放振动反馈 */
    private fun vibrate(durationMs: Long = 30) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /** 播放出牌音效 */
    fun playCardSound() {
        playTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    /** 播放错误音效 */
    fun playErrorSound() {
        playTone(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150)
        vibrate(50)
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
        if (gameEngine.stateMachine.phase == GamePhase.PLAYING) return true
        return messageText.isNotEmpty() || errorText.isNotEmpty()
    }

    /** 本帧已绘制完成（仅当恰好有一个待处理请求时清空，避免丢失绘制期间新到达的 refresh） */
    fun onFrameDrawn() {
        redrawRequests.compareAndSet(1, 0)
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

    /** 设置计分 */
    fun setTotalScore(score: Int) {
        totalScore = score
    }

    fun getTotalScore(): Int = totalScore

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
                // 检查按钮点击（记录动作而非索引，防止绘制线程重建按钮导致错位）
                for (btn in buttons) {
                    if (btn.rect.contains(x, y)) {
                        pressedAction = btn.action
                        vibrate(20)
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
                // 滑动选牌：位移超过阈值后持续添加选中的牌（只加不删，避免误取消）
                if (isDragging && gameEngine.stateMachine.phase == GamePhase.PLAYING
                    && gameEngine.stateMachine.currentPlayerIndex == 0
                    && movedBeyondSlop(x, y)) {
                    addCardFromTouch(x, y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 处理按钮释放（允许轻微偏移，防误触）
                val action = pressedAction
                if (action != null) {
                    handleButtonAction(action)
                    vibrate(20)
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
                    refresh()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
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
        } else {
            gameEngine.selectedCardIndices.add(idx)
            playTone(ToneGenerator.TONE_PROP_ACK, 30)
        }
        refresh()
    }

    /** 滑动：向滑动经过的牌追加选中 */
    private fun addCardFromTouch(touchX: Float, touchY: Float) {
        val idx = hitTestCard(touchX, touchY)
        if (idx < 0) return
        if (idx !in gameEngine.selectedCardIndices) {
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
                    // 检查是否能管上家
                    val lastGroup = gameEngine.stateMachine.lastPlayedGroup
                    val lastPlayer = gameEngine.stateMachine.lastPlayedPlayerIndex
                    if (lastGroup != null && lastPlayer != 0) {
                        if (com.doudizhu.game.logic.CardRuleEngine.compare(group, lastGroup) <= 0) {
                            showError("打不过上家的牌，请重新选择", 2000)
                            return
                        }
                    }
                    // 合法出牌，清空桌面并出牌
                    clearAllPlayedCards()
                    playCardSound()
                    gameEngine.humanPlay(selectedCards)
                } else {
                    showError("请先选择要出的牌", 1500)
                }
            }
            action == "pass" -> gameEngine.humanPass()
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
                gameEngine.humanBid(action.removePrefix("bid_").toInt())
                playTone(ToneGenerator.TONE_PROP_ACK, 50)
            }
            action == "restart" -> {
                clearAllPlayedCards()
                gameEngine.startNewGame()
            }
        }
    }

    // ==================== 主绘制 ====================

    fun drawGame(canvas: Canvas) {
        if (screenWidth == 0 || screenHeight == 0) return
        buttons.clear()
        highlightFrame = (highlightFrame + 1) % 60

        // 每帧重新计算手牌间距
        recalcSizes()

        drawBackground(canvas)
        drawTopBar(canvas)
        drawBottomCards(canvas)

        // 横屏布局：AI在左右两侧
        drawAIPlayer(canvas, 1, screenWidth - 260f, 100f)   // 右侧AI
        drawAIPlayer(canvas, 2, 260f, 100f)                  // 左侧AI

        // 桌面中央展示所有玩家出的牌
        drawTablePlayedCards(canvas)

        // 底部人类手牌
        drawHumanHand(canvas)

        // 当前回合高亮提示
        drawTurnHighlight(canvas)

        // 按钮
        drawButtons(canvas)

        // 消息
        drawMessage(canvas)

        // 错误提示
        drawError(canvas)

        // 计分显示
        drawScore(canvas)

        // 结算时显示所有玩家剩余牌（正面）
        if (gameEngine.stateMachine.phase == GamePhase.GAME_OVER || 
            gameEngine.stateMachine.phase == GamePhase.SETTLING) {
            drawAllRemainingCards(canvas)
        }
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
        textPaint.textSize = 52f
        textPaint.color = Color.WHITE
        canvas.drawText("斗地主", screenWidth / 2f, 58f, textPaint)
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

        textPaint.textSize = 40f
        textPaint.color = Color.parseColor("#FFD600")
        canvas.drawText("底牌", screenWidth / 2f, y + 6f, textPaint)

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

        // 名称（放大2.5倍: 32*2.5=80 -> 用60f合适）
        textPaint.textSize = 50f
        textPaint.color = Color.WHITE
        canvas.drawText(player.name, centerX, topY + 55f, textPaint)

        // 剩余张数（放大2.5倍: 30*2.5=75 -> 用50f）
        textPaint.textSize = 46f
        textPaint.color = Color.parseColor("#FFD600")
        canvas.drawText("${player.cardCount}张", centerX, topY + 110f, textPaint)

        // 角色（放大2.5倍: 24*2.5=60 -> 用40f）
        val roleText = if (player.role == com.doudizhu.game.model.PlayerRole.LANDLORD) "地主" else "农民"
        textPaint.textSize = 38f
        textPaint.color = if (player.role == com.doudizhu.game.model.PlayerRole.LANDLORD)
            Color.parseColor("#FFD600") else Color.parseColor("#A5D6A7")
        canvas.drawText(roleText, centerX, topY + 160f, textPaint)

        // 牌背扇形（放大2倍）
        val count = minOf(player.cardCount, 12)
        val backSpacing = aiBackW * 0.55f
        val totalBackW = backSpacing * (count - 1) + aiBackW
        val backStartX = centerX - totalBackW / 2
        for (i in 0 until count) {
            drawCardBack(canvas, backStartX + i * backSpacing, topY + panelH + 16f, aiBackW, aiBackH)
        }
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

    /** 底部人类手牌（间距填满90%） */
    private fun drawHumanHand(canvas: Canvas) {
        val hand = gameEngine.players[0].handCards
        if (hand.isEmpty()) return

        val totalWidth = handSpacing * (hand.size - 1) + cardW
        val startX = (screenWidth - totalWidth) / 2
        val baseY = screenHeight - cardH - 40f

        val centerIdx = (hand.size - 1) / 2.0

        for ((i, card) in hand.withIndex()) {
            val isSelected = i in gameEngine.selectedCardIndices
            val cx = startX + i * handSpacing

            // 微弧效果
            val distFromCenter = i - centerIdx
            val yOffset = (-cos(distFromCenter * 0.04) * 6.0 + 6.0).toFloat()
            val rotation = (distFromCenter * 0.8).toFloat()

            val cy = baseY - (if (isSelected) 60f else 0f) - yOffset
            drawCard(canvas, cx, cy, card, isSelected, rotation)
        }
    }

    /** 绘制单张扑克牌（手牌，大尺寸） */
    private fun drawCard(canvas: Canvas, x: Float, y: Float, card: Card, selected: Boolean, rotation: Float = 0f) {
        canvas.save()
        canvas.rotate(rotation, x + cardW / 2, y + cardH / 2)

        // 选中阴影
        if (selected) {
            canvas.drawRoundRect(x + 4f, y + 8f, x + cardW + 4f, y + cardH + 8f,
                cardRadius, cardRadius, shadowPaint)
        }

        // 牌面
        cardPaint.color = if (selected) Color.parseColor("#FFF9C4") else Color.WHITE
        canvas.drawRoundRect(x, y, x + cardW, y + cardH, cardRadius, cardRadius, cardPaint)

        // 边框
        cardBorderPaint.color = if (selected) Color.parseColor("#FFD600") else Color.parseColor("#BDBDBD")
        cardBorderPaint.strokeWidth = if (selected) 6f else 3f
        canvas.drawRoundRect(x, y, x + cardW, y + cardH, cardRadius, cardRadius, cardBorderPaint)

        val textColor = if (card.isRed) Color.parseColor("#D32F2F") else Color.parseColor("#212121")

        // 左上角花色+点数
        smallTextPaint.textSize = cardW * 0.30f
        smallTextPaint.color = textColor
        smallTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(card.suitSymbol, x + 14f, y + cardH * 0.18f, smallTextPaint)
        smallTextPaint.textSize = cardW * 0.36f
        canvas.drawText(card.displayText, x + 14f, y + cardH * 0.36f, smallTextPaint)

        // 中央大字
        textPaint.textSize = cardW * 0.62f
        textPaint.color = textColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(card.displayText, x + cardW / 2, y + cardH * 0.62f, textPaint)

        // 右下角花色
        smallTextPaint.textSize = cardW * 0.30f
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
        // 在桌面下方显示所有玩家剩余牌
        val showY = screenHeight * 0.58f
        val miniW = 60f
        val miniH = 84f
        val miniGap = 8f

        // 左侧AI剩余牌
        val leftCards = gameEngine.players[2].handCards
        if (leftCards.isNotEmpty()) {
            textPaint.textSize = 28f
            textPaint.color = Color.parseColor("#A5D6A7")
            canvas.drawText("电脑B剩余", screenWidth * 0.18f, showY - 10f, textPaint)
            val totalW = (miniW + miniGap) * leftCards.size - miniGap
            val startX = screenWidth * 0.18f - totalW / 2
            for ((i, card) in leftCards.withIndex()) {
                drawMiniCard(canvas, startX + i * (miniW + miniGap), showY, miniW, miniH, card, faceUp = true)
            }
        }

        // 右侧AI剩余牌
        val rightCards = gameEngine.players[1].handCards
        if (rightCards.isNotEmpty()) {
            textPaint.textSize = 28f
            textPaint.color = Color.parseColor("#A5D6A7")
            canvas.drawText("电脑A剩余", screenWidth * 0.82f, showY - 10f, textPaint)
            val totalW = (miniW + miniGap) * rightCards.size - miniGap
            val startX = screenWidth * 0.82f - totalW / 2
            for ((i, card) in rightCards.withIndex()) {
                drawMiniCard(canvas, startX + i * (miniW + miniGap), showY, miniW, miniH, card, faceUp = true)
            }
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

                // 提示文字
                textPaint.textSize = 44f
                textPaint.color = Color.parseColor("#FFEB3B")
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("轮到你出牌", screenWidth / 2f, screenHeight * 0.52f, textPaint)
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
                textPaint.textSize = 40f
                textPaint.color = Color.parseColor("#FFB74D")
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("电脑A思考中...", screenWidth / 2f, screenHeight * 0.52f, textPaint)
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
                textPaint.textSize = 40f
                textPaint.color = Color.parseColor("#FFB74D")
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("电脑B思考中...", screenWidth / 2f, screenHeight * 0.52f, textPaint)
            }
        }
    }

    // ==================== 按钮（放大2倍 + 按压变色反馈 + 位置上移） ====================

    private fun drawButtons(canvas: Canvas) {
        val phase = gameEngine.stateMachine.phase
        val currentPlayer = gameEngine.stateMachine.currentPlayerIndex

        // 按钮尺寸（放大2倍）
        var btnW = 300f
        val btnH = 110f
        val gap = 36f

        when {
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
                val y = screenHeight - cardH - 220f  // 位置上移

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
                    addButton(canvas, labels[s]!!, startX + i * (btnW + gap), y, btnW, btnH,
                        colors[s]!!, pressedColors[s]!!, "bid_$s")
                }
            }
            // 出牌阶段
            phase == GamePhase.PLAYING && currentPlayer == 0 -> {
                val canPass = !gameEngine.stateMachine.mustPlay()
                val y = screenHeight - cardH - 220f  // 位置上移

                if (canPass) {
                    val totalW = btnW * 3 + gap * 2
                    val startX = (screenWidth - totalW) / 2
                    addButton(canvas, "不出", startX, y, btnW, btnH, Color.parseColor("#616161"),
                        Color.parseColor("#424242"), "pass")
                    addButton(canvas, "提示", startX + btnW + gap, y, btnW, btnH, Color.parseColor("#1976D2"),
                        Color.parseColor("#0D47A1"), "hint")
                    addButton(canvas, "出牌", startX + (btnW + gap) * 2, y, btnW, btnH, Color.parseColor("#388E3C"),
                        Color.parseColor("#1B5E20"), "play")
                } else {
                    val totalW = btnW * 2 + gap
                    val startX = (screenWidth - totalW) / 2
                    addButton(canvas, "提示", startX, y, btnW, btnH, Color.parseColor("#1976D2"),
                        Color.parseColor("#0D47A1"), "hint")
                    addButton(canvas, "出牌", startX + btnW + gap, y, btnW, btnH, Color.parseColor("#388E3C"),
                        Color.parseColor("#1B5E20"), "play")
                }
            }
            // 游戏结束
            phase == GamePhase.GAME_OVER || phase == GamePhase.SETTLING -> {
                val bigW = 400f
                val bigH = 120f
                addButton(canvas, "再来一局", (screenWidth - bigW) / 2, screenHeight / 2 + 100f,
                    bigW, bigH, Color.parseColor("#388E3C"), Color.parseColor("#1B5E20"), "restart")
            }
        }
    }

    private fun addButton(canvas: Canvas, text: String, x: Float, y: Float,
                          w: Float, h: Float, color: Int, pressedColor: Int, action: String) {
        val rect = RectF(x, y, x + w, y + h)
        buttons.add(ButtonRect(text, rect, color, pressedColor, action))

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

        buttonTextPaint.textSize = 52f
        canvas.drawText(text, x + w / 2, y + h / 2 + 18f, buttonTextPaint)
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

    /** 计分显示 */
    private fun drawScore(canvas: Canvas) {
        textPaint.textSize = 38f
        textPaint.color = Color.parseColor("#FFFFFF")
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("积分: $totalScore", screenWidth - 28f, 56f, textPaint)
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
