package com.doudizhu.game.logic

import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.model.Player
import com.doudizhu.game.model.PlayerRole
import com.doudizhu.game.model.createDeck
import com.doudizhu.game.state.GamePhase
import com.doudizhu.game.state.GameStateMachine

/**
 * 游戏引擎回调接口
 * 用于通知UI层游戏状态变化
 */
interface GameEngineCallback {
    /** 发牌完成 */
    fun onDealingComplete()
    /** 叫地主阶段开始 */
    fun onBiddingStart(playerIndex: Int)
    /** 玩家叫分 */
    fun onPlayerBid(playerIndex: Int, score: Int)
    /** 叫地主结束，地主确定 */
    fun onBiddingComplete(landlordIndex: Int, bottomCards: List<Card>)
    /** 轮到某玩家出牌 */
    fun onPlayerTurn(playerIndex: Int)
    /** 玩家出牌 */
    fun onPlayerPlay(playerIndex: Int, cards: List<Card>, group: CardGroup)
    /** 玩家不出 */
    fun onPlayerPass(playerIndex: Int)
    /** 游戏结束 */
    fun onGameOver(winnerIndex: Int, isLandlordWin: Boolean)
    /** 请求刷新UI */
    fun onRequestRefresh()
}

/**
 * 游戏引擎
 * 控制整体游戏流程，协调状态机、规则引擎和AI
 */
class GameEngine {

    /** 三位玩家 */
    val players = listOf(
        Player(0, "你", isHuman = true),
        Player(1, "电脑A", isHuman = false, difficulty = Difficulty.NORMAL),
        Player(2, "电脑B", isHuman = false, difficulty = Difficulty.NORMAL)
    )

    /** 游戏状态机 */
    val stateMachine = GameStateMachine()

    /** 当前 AI 难度（由主界面难度选择决定，新开对局时应用到两个 AI 玩家） */
    var aiDifficulty: Difficulty = Difficulty.NORMAL

    /** 回调接口 */
    var callback: GameEngineCallback? = null

    /** 当前选中的手牌索引集合（CopyOnWrite：绘制线程与UI线程并发读写安全） */
    val selectedCardIndices = java.util.concurrent.CopyOnWriteArraySet<Int>()

    /** 3 位玩家各 rank 已打出张数（仅凭公开出牌记录推算，不做不可见推断） */
    private val playedByPlayer: Array<IntArray> = Array(3) { IntArray(18) }

    /**
     * 大师模式搜索的「局代号」：每次开新局 / 退回主界面都自增，
     * 用于丢弃后台搜索线程产出的过期结果（避免上一局残局的决策被误用到新局）。
     */
    private var searchEpoch = 0

    /** 大师模式搜索后台线程（单线程串行，避免同时跑多个搜索） */
    private val masterSearchExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    /** 大师 AI 出牌前的「思考延迟」（毫秒），仅用于节奏，与搜索耗时叠加 */
    private val MASTER_DELAY_MS = 500L
    /** 大师 AI 后台搜索时长预算（毫秒） */
    private val MASTER_DEADLINE_MS = 2000L
    /** 大师 AI 后台搜索节点预算 */
    private val MASTER_NODE_LIMIT = 6_000_000
    /**
     * 电脑玩家单回合最短耗时（毫秒）：从轮到自己起算，若「思考 + 决策」不足此时长，
     * 则补足延时后再出牌，避免电脑出牌过快导致玩家看不清节奏。
     * 只作用于电脑自动出牌链路，不影响玩家点「提示」（getHint 为同步调用，与此无关）。
     */
    private val MIN_AI_TURN_MS = 1500L
    /** 提示（getHint）同步搜索时长预算（毫秒），必须小以免卡 UI */
    private val HINT_DEADLINE_MS = 300L
    /** 提示（getHint）同步搜索节点预算 */
    private val HINT_NODE_LIMIT = 450_000

    /**
     * 开始新游戏
     */
    fun startNewGame() {
        // 新局：使任何在途的后台搜索结果作废
        searchEpoch++
        // 重置所有玩家状态
        players.forEach {
            it.handCards.clear()
            it.role = PlayerRole.FARMER
            it.bidScore = 0
            if (!it.isHuman) it.difficulty = aiDifficulty
        }
        // 清零出牌记录
        playedByPlayer.forEach { it.fill(0) }
        selectedCardIndices.clear()

        // 重置计分
        stateMachine.currentBidScore = 0

        // 初始化状态机
        stateMachine.startDealing()

        // 洗牌发牌
        dealCards()

        // 通知UI发牌完成（由UI触发 3 秒手牌展示动画，动画结束后再进入叫分阶段）
        callback?.onDealingComplete()
    }

    /**
     * 返回主界面（难度选择）：清空手牌并进入 MENU 阶段
     */
    fun returnToMenu() {
        // 退回主界面同样废弃在途后台搜索
        searchEpoch++
        players.forEach { it.handCards.clear() }
        stateMachine.startMenu()
    }

    /**
     * 进入叫地主阶段（在开局手牌展示动画结束后由 UI 回调）
     * 随机选第一个叫分玩家，若 AI 先叫则自动处理
     */
    fun startBiddingPhase() {
        val firstBidder = (0..2).random()
        stateMachine.startBidding(firstBidder)
        callback?.onBiddingStart(firstBidder)

        // 如果是AI先叫，自动处理
        if (!players[firstBidder].isHuman) {
            scheduleAIBid(firstBidder)
        }
    }

    /**
     * 洗牌并发牌（每人17张，底牌3张）
     */
    private fun dealCards() {
        val deck = createDeck()
        deck.shuffle()

        // 每人17张
        for (i in 0 until 51) {
            players[i % 3].handCards.add(deck[i])
        }

        // 底牌3张
        stateMachine.bottomCards.clear()
        stateMachine.bottomCards.addAll(deck.subList(51, 54))

        // 手牌排序
        players.forEach { it.handCards.sortBy { card -> card.rank } }
    }

    /**
     * 人类玩家叫分
     * @param score 叫分值（0=不叫，1~3=叫分）
     */
    fun humanBid(score: Int) {
        if (stateMachine.phase != GamePhase.BIDDING) return
        if (stateMachine.currentPlayerIndex != 0) return

        processBid(0, score)
    }

    /**
     * AI自动叫分
     */
    private fun scheduleAIBid(playerIndex: Int) {
        // 延迟模拟思考
        val player = players[playerIndex]
        val score = calculateAIBid(player)

        // 使用postDelayed模拟延迟（简化处理，直接执行）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            processBid(playerIndex, score)
        }, 1000)
    }

    /**
     * AI计算叫分值（使用智能决策）
     */
    private fun calculateAIBid(player: Player): Int {
        val hand = player.handCards
        val currentMaxBid = stateMachine.currentMaxBid
        // 大师模式走 MasterAIDecision（其叫分复用更激进的 MASTER 阈值），其余走普通决策
        return if (player.difficulty == Difficulty.MASTER) {
            MasterAIDecision.decideBid(hand, currentMaxBid)
        } else {
            AIDecision.decideBid(hand, currentMaxBid, player.difficulty)
        }
    }

    /**
     * 处理叫分逻辑
     */
    private fun processBid(playerIndex: Int, score: Int) {
        // 叫分必须高于当前最高分，非法叫分一律视为不叫（与状态机规则保持一致）
        val realScore = if (score > 0 && score <= stateMachine.currentMaxBid) 0 else score
        players[playerIndex].bidScore = realScore
        callback?.onPlayerBid(playerIndex, realScore)

        val biddingDone = stateMachine.processBid(playerIndex, realScore)

        if (biddingDone) {
            finalizeBidding()
        } else {
            val nextPlayer = stateMachine.currentPlayerIndex
            callback?.onBiddingStart(nextPlayer)
            if (!players[nextPlayer].isHuman) {
                scheduleAIBid(nextPlayer)
            }
        }
        callback?.onRequestRefresh()
    }

    /**
     * 完成叫地主阶段
     */
    private fun finalizeBidding() {
        if (stateMachine.hasLandlord) {
            val li = stateMachine.landlordIndex
            // 设置地主角色
            players[li].role = PlayerRole.LANDLORD
            // 底牌给地主
            players[li].handCards.addAll(stateMachine.bottomCards)
            players[li].handCards.sortBy { it.rank }
            // 记录底分
            stateMachine.currentBidScore = stateMachine.currentMaxBid

            callback?.onBiddingComplete(li, stateMachine.bottomCards.toList())

            // 进入出牌阶段
            stateMachine.startPlaying()
            callback?.onPlayerTurn(li)

            // 如果地主是AI
            if (!players[li].isHuman) {
                scheduleAIPlay(li)
            }
        } else {
            // 没人叫地主，重新发牌
            startNewGame()
        }
    }

    /**
     * 人类玩家出牌
     * @param selectedCards 选中的牌列表
     */
    fun humanPlay(selectedCards: List<Card>) {
        if (stateMachine.phase != GamePhase.PLAYING) return
        if (stateMachine.currentPlayerIndex != 0) return

        val group = CardRuleEngine.identify(selectedCards)
        if (group.type == CardType.INVALID) return

        // 校验合法性
        if (!CardRuleEngine.isValidPlay(group, stateMachine.lastPlayedGroup)) return

        processPlay(0, selectedCards, group)
    }

    /**
     * 人类玩家不出
     */
    fun humanPass() {
        if (stateMachine.phase != GamePhase.PLAYING) return
        if (stateMachine.currentPlayerIndex != 0) return
        if (stateMachine.mustPlay()) return  // 必须出牌时不能过

        processPass(0)
    }

    /**
     * 处理出牌
     */
    private fun processPlay(playerIndex: Int, cards: List<Card>, group: CardGroup) {
        // 从手牌中移除
        val player = players[playerIndex]
        cards.forEach { card ->
            player.handCards.removeIf { it.id == card.id }
        }

        // 记录每 rank 已打出张数（用于对手高牌推演）
        cards.forEach { playedByPlayer[playerIndex][it.rank]++ }

        // 更新状态
        val nextPlayer = stateMachine.processPlay(playerIndex, group)
        selectedCardIndices.clear()

        callback?.onPlayerPlay(playerIndex, cards, group)

        // 检查是否结束
        val winner = stateMachine.checkGameOver(players.map { it.cardCount })
        if (winner >= 0) {
            stateMachine.startSettling()
            val isLandlordWin = players[winner].role == PlayerRole.LANDLORD
            callback?.onGameOver(winner, isLandlordWin)
            stateMachine.endGame()
            return
        }

        stateMachine.currentPlayerIndex = nextPlayer
        callback?.onPlayerTurn(nextPlayer)

        if (!players[nextPlayer].isHuman) {
            scheduleAIPlay(nextPlayer)
        }
        callback?.onRequestRefresh()
    }

    /**
     * 处理不出
     */
    private fun processPass(playerIndex: Int) {
        val nextPlayer = stateMachine.processPass(playerIndex)
        callback?.onPlayerPass(playerIndex)

        stateMachine.currentPlayerIndex = nextPlayer
        callback?.onPlayerTurn(nextPlayer)

        if (!players[nextPlayer].isHuman) {
            scheduleAIPlay(nextPlayer)
        }
        callback?.onRequestRefresh()
    }

    /**
     * AI自动出牌
     */
    private fun scheduleAIPlay(playerIndex: Int) {
        // 「轮到自己」的时刻：用于保证单回合总耗时不少于 MIN_AI_TURN_MS
        val turnStartMs = System.currentTimeMillis()
        val epoch = searchEpoch
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (stateMachine.phase != GamePhase.PLAYING) return@postDelayed
            if (stateMachine.currentPlayerIndex != playerIndex) return@postDelayed

            val player = players[playerIndex]

            if (player.difficulty == Difficulty.MASTER) {
                // 大师模式：在后台线程跑完美信息搜索，主线程回调节点应用，避免卡 UI
                scheduleMasterPlay(playerIndex, turnStartMs)
            } else {
                // 普通模式：走 AIDecision 启发式（语义与改动前完全一致）
                val lastPlay = stateMachine.lastPlayedGroup
                val teammateCount = if (player.role == PlayerRole.FARMER) {
                    getCardCount(getTeammateIndex(playerIndex))
                } else 0
                val decision = AIDecision.decide(
                    player.handCards, lastPlay, player.difficulty, player.role, teammateCount,
                    lastPlayerIndex = stateMachine.lastPlayedPlayerIndex,
                    myIndex = playerIndex,
                    opponentCardCounts = getOpponentCardCounts(playerIndex),
                    landlordIndex = stateMachine.landlordIndex,
                    unseenCounts = getUnseenRankCounts(playerIndex),
                    perPlayerPlayed = playedByPlayer.map { it.clone() }.toTypedArray(),
                    primaryOpponentIndex = getPrimaryOpponentIndex(playerIndex),
                    teammateIndex = getTeammateIndex(playerIndex)
                )
                applyAIDecisionPaced(playerIndex, decision, lastPlay, turnStartMs, epoch)
            }
        }, if (players[playerIndex].difficulty == Difficulty.MASTER) MASTER_DELAY_MS else 1200L)
    }

    /**
     * 按最短回合时长节流后再应用电脑决策：
     * 若从轮到自己（[turnStartMs]）到决策完成不足 [MIN_AI_TURN_MS]，则补足剩余时间再出牌。
     * 延时期间局面可能变化（新局 / 换人 / 阶段切换），触发时需重新校验。
     */
    private fun applyAIDecisionPaced(
        playerIndex: Int, decision: CardGroup?, lastPlay: CardGroup?,
        turnStartMs: Long, epoch: Int
    ) {
        val remain = MIN_AI_TURN_MS - (System.currentTimeMillis() - turnStartMs)
        if (remain <= 0L) {
            applyAIDecision(playerIndex, decision, lastPlay)
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (epoch != searchEpoch) return@postDelayed
            if (stateMachine.phase != GamePhase.PLAYING) return@postDelayed
            if (stateMachine.currentPlayerIndex != playerIndex) return@postDelayed
            applyAIDecision(playerIndex, decision, lastPlay)
        }, remain)
    }

    /**
     * 大师模式：构造全量快照后在后台线程求解，主线程回调应用结果。
     * 用 [searchEpoch] 防止过期结果被误用到新局。
     */
    private fun scheduleMasterPlay(playerIndex: Int, turnStartMs: Long) {
        val snapshot = buildMasterSnapshot(playerIndex)
        val epoch = searchEpoch
        masterSearchExecutor.execute {
            val decision = MasterAIDecision.decide(
                snapshot,
                deadlineMs = MASTER_DEADLINE_MS,
                nodeLimit = MASTER_NODE_LIMIT
            )
            // 切回主线程应用，并二次校验局面未被新局/换人改变
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (epoch != searchEpoch) return@post
                if (stateMachine.phase != GamePhase.PLAYING) return@post
                if (stateMachine.currentPlayerIndex != playerIndex) return@post
                applyAIDecisionPaced(playerIndex, decision, stateMachine.lastPlayedGroup, turnStartMs, epoch)
            }
        }
    }

    /**
     * 统一应用 AI 决策（普通 / 大师共用）：
     *  - decision 合法且非 INVALID → 出牌；
     *  - 否则本轮首家（mustPlay）必须出 → 兜底出最小单张；
     *  - 否则过牌。
     * 语义与改动前普通模式分支完全一致，大师模式复用同一套兜底。
     */
    private fun applyAIDecision(playerIndex: Int, decision: CardGroup?, lastPlay: CardGroup?) {
        val player = players[playerIndex]
        val aiMustPlay = stateMachine.mustPlay()
        if (decision != null && decision.type != CardType.INVALID) {
            processPlay(playerIndex, decision.cards, decision)
        } else if (aiMustPlay) {
            // 极端情况下决策异常仍兜底出最小的单张
            val fallback = player.handCards.minByOrNull { it.rank }
            if (fallback != null) {
                val single = CardGroup(
                    CardType.SINGLE,
                    fallback.rank, 1, listOf(fallback)
                )
                processPlay(playerIndex, listOf(fallback), single)
            } else {
                processPass(playerIndex)
            }
        } else {
            processPass(playerIndex)
        }
    }

    /**
     * 获取对手手牌数（用于AI决策，仅凭公开出牌记录推导，不读对手隐藏手牌）
     * 地主：两个农民都是对手
     * 农民：唯一对手是地主（队友不算对手）
     */
    private fun getOpponentCardCounts(myIndex: Int): IntArray {
        val player = players[myIndex]
        return if (player.role == PlayerRole.FARMER) {
            val landlord = players.firstOrNull { it.role == PlayerRole.LANDLORD }
            intArrayOf(landlord?.let { legalCardCount(it.index) } ?: 0)
        } else {
            intArrayOf(
                legalCardCount((myIndex + 1) % 3),
                legalCardCount((myIndex + 2) % 3)
            )
        }
    }

    /**
     * 仅凭合法信息推导某玩家当前手牌数：
     * 初始张数（地主20/农民17）- 该玩家已打出的牌数（公共记录）
     */
    private fun legalCardCount(playerIndex: Int): Int {
        val base = if (stateMachine.hasLandlord && stateMachine.landlordIndex == playerIndex) 20 else 17
        var played = 0
        for ((p, cards) in stateMachine.playHistory) {
            if (p == playerIndex) played += cards.size
        }
        return (base - played).coerceAtLeast(0)
    }

/**
     * 获取 AI 未见的各点数剩余张数（仅凭合法信息推导）
     * 推导方式：54张牌中每个rank的总数 - 自己手牌中该rank张数 - 已打出的该rank张数
     * = 另外两位玩家手牌中该rank的张数（可被合法精确推导）
     * @return 长度为18的数组（下标即rank），索引0~2无意义
     */
    private fun getUnseenRankCounts(myIndex: Int): IntArray {
        val known = IntArray(18)
        // 自己手牌（合法可见）
        players[myIndex].handCards.forEach { known[it.rank]++ }
        // 已打出的牌（公共可见记录）
        stateMachine.playHistory.forEach { (_, cards) -> cards.forEach { known[it.rank]++ } }

        val counts = IntArray(18)
        for (rank in 3..17) {
            val total = if (rank == 16 || rank == 17) 1 else 4
            counts[rank] = (total - known[rank]).coerceAtLeast(0)
        }
        return counts
    }

    /**
     * 获取我方视角下的队友索引：
     *  - 地主返回 -1
     *  - 农民返回「非我且非地主的玩家」
     */
    fun getTeammateIndex(myIndex: Int): Int {
        if (players[myIndex].role != PlayerRole.FARMER) return -1
        val landlord = stateMachine.landlordIndex
        return (0..2).firstOrNull { it != myIndex && it != landlord } ?: -1
    }

    /**
     * 获取我方的主要对手索引：
     *  - 农民为地主索引
     *  - 地主为右手农民索引（简化，取轮转下一位）
     */
    fun getPrimaryOpponentIndex(myIndex: Int): Int {
        if (stateMachine.landlordIndex < 0) return -1
        if (players[myIndex].role == PlayerRole.FARMER) return stateMachine.landlordIndex
        return (myIndex + 1) % 3
    }

    /**
     * 构造大师模式所需的全量快照（含三家真实手牌），仅 MASTER 难度调用。
     * 搜索求解器基于全量手牌做完美信息推演，故只需手牌与「上一手 / 轮到谁」，
     * 不再需要出牌历史与大师集合（两步模拟已废弃）。
     */
    private fun buildMasterSnapshot(myIndex: Int): MasterAIDecision.Snapshot {
        val role = players[myIndex].role
        val landlordIndex = stateMachine.landlordIndex
        val hands = players.associate { it.index to it.handCards.toList() }
        val teammateIsMaster = role == PlayerRole.FARMER &&
            players.any { it.index != myIndex && it.index != landlordIndex && it.difficulty == Difficulty.MASTER }
        // 对手是否含人类：地主的对手是两家农民，农民的对手是地主。
        // 该字段仅在地主根（rootIsLandlord）时生效，用于提升地主面对人类农民时的获胜率，
        // 不影响「2个AI都是农民」的协作局，也不影响普通模式。
        val opponents = if (role == PlayerRole.LANDLORD) {
            players.filter { it.index != myIndex && it.index != landlordIndex }
        } else {
            players.filter { it.index == landlordIndex }
        }
        val opponentsHuman = opponents.any { it.isHuman }
        // 仅地主根时，记录人类农民的索引（供地主搜索做对手建模）。农民根恒为 -1，
        // 因此农民方搜索与普通模式永远不会进入人类建模分支。
        val humanFarmerIndex = if (role == PlayerRole.LANDLORD) {
            opponents.firstOrNull { it.isHuman }?.index ?: -1
        } else {
            -1
        }
        return MasterAIDecision.Snapshot(
            myIndex = myIndex,
            role = role,
            landlordIndex = landlordIndex,
            hands = hands,
            lastPlay = stateMachine.lastPlayedGroup,
            lastPlayerIndex = stateMachine.lastPlayedPlayerIndex,
            currentPlayerIndex = stateMachine.currentPlayerIndex,
            teammateIsMaster = teammateIsMaster,
            opponentsHuman = opponentsHuman,
            humanFarmerIndex = humanFarmerIndex
        )
    }

    /**
     * 获取某个玩家的合法剩余手牌数
     */
    fun getCardCount(index: Int): Int = legalCardCount(index)

    /**
     * 获取AI建议的最优出牌组合（给人类玩家用）
     * 与当前模式一致：
     *  - 大师模式：使用全信息求解器 [MasterAIDecision]（与大师 AI 同策略，含一步模拟对手/残局拦截等）
     *  - 普通模式：使用普通 AI 启发式 [AIDecision]
     * 底牌/跟牌兜底逻辑保持一致。
     */
    fun getHint(): List<Card>? {
        val hand = players[0].handCards
        val lastPlay = stateMachine.lastPlayedGroup

        // 提示策略跟随当前难度：大师模式用全信息求解器（小预算同步搜索，避免卡 UI），普通模式用普通 AI
        val decision = if (aiDifficulty == Difficulty.MASTER) {
            MasterAIDecision.decide(
                buildMasterSnapshot(0),
                deadlineMs = HINT_DEADLINE_MS,
                nodeLimit = HINT_NODE_LIMIT
            )
        } else {
            AIDecision.decide(
                hand, lastPlay, Difficulty.NORMAL, players[0].role, getCardCount(getTeammateIndex(0)),
                lastPlayerIndex = stateMachine.lastPlayedPlayerIndex,
                myIndex = 0,
                opponentCardCounts = getOpponentCardCounts(0),
                landlordIndex = stateMachine.landlordIndex,
                unseenCounts = getUnseenRankCounts(0),
                perPlayerPlayed = playedByPlayer.map { it.clone() }.toTypedArray(),
                primaryOpponentIndex = getPrimaryOpponentIndex(0),
                teammateIndex = getTeammateIndex(0)
            )
        }
        if (decision != null && decision.type != CardType.INVALID) {
            return decision.cards
        }
        // AI 决策返回 null（如保存炸弹不跟）但玩家手上确实有可出的牌时，
        // 提示一个最小的合法出牌（优先非炸弹），避免误报「没有能出的牌」
        if (lastPlay != null && lastPlay.type != CardType.INVALID) {
            val legal = CardRuleEngine.findAllValidPlays(hand, lastPlay)
            if (legal.isNotEmpty()) {
                val nonBomb = legal.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
                val pick = nonBomb.minByOrNull { it.mainRank }
                    ?: legal.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }.minByOrNull { it.mainRank }
                if (pick != null) return pick.cards
            }
        }
        // 无最优选择时（跟牌阶段没有能压的牌），返回 null 让 UI 提示「没有能出的牌」
        return null
    }
}
