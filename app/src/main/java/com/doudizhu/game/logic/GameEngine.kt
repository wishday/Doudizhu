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
    fun onPlayerPlay(playerIndex: Int, cards: List<Card>)
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
        Player(2, "电脑B", isHuman = false, difficulty = Difficulty.EASY)
    )

    /** 游戏状态机 */
    val stateMachine = GameStateMachine()

    /** 回调接口 */
    var callback: GameEngineCallback? = null

    /** 当前选中的手牌索引集合 */
    val selectedCardIndices = mutableSetOf<Int>()

    /**
     * 开始新游戏
     */
    fun startNewGame() {
        // 重置所有玩家状态
        players.forEach {
            it.handCards.clear()
            it.role = PlayerRole.FARMER
            it.bidScore = 0
        }
        selectedCardIndices.clear()

        // 重置计分
        stateMachine.currentBidScore = 0

        // 初始化状态机
        stateMachine.startDealing()

        // 洗牌发牌
        dealCards()

        // 通知UI发牌完成
        callback?.onDealingComplete()

        // 进入叫地主阶段（随机选第一个叫分玩家）
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
        // 使用AI决策引擎的智能叫分
        return AIDecision.decideBid(hand, currentMaxBid)
    }

    /**
     * 处理叫分逻辑
     */
    private fun processBid(playerIndex: Int, score: Int) {
        players[playerIndex].bidScore = score
        callback?.onPlayerBid(playerIndex, score)

        val biddingDone = stateMachine.processBid(playerIndex, score)

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

        // 更新状态
        val nextPlayer = stateMachine.processPlay(playerIndex, group)
        selectedCardIndices.clear()

        callback?.onPlayerPlay(playerIndex, cards)

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
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (stateMachine.phase != GamePhase.PLAYING) return@postDelayed
            if (stateMachine.currentPlayerIndex != playerIndex) return@postDelayed

            val player = players[playerIndex]
            val lastPlay = stateMachine.lastPlayedGroup

            // 计算队友手牌数（农民时）
            val teammateCount = if (player.role == PlayerRole.FARMER) {
                val teammate = players.firstOrNull { it.index != playerIndex && it.role == PlayerRole.FARMER }
                teammate?.cardCount ?: 0
            } else 0

            val decision = AIDecision.decide(
                player.handCards, lastPlay, player.difficulty, player.role, teammateCount,
                lastPlayerIndex = stateMachine.lastPlayedPlayerIndex,
                myIndex = playerIndex,
                opponentCardCounts = getOpponentCardCounts(playerIndex)
            )

            if (decision != null && decision.type != CardType.INVALID) {
                processPlay(playerIndex, decision.cards, decision)
            } else {
                processPass(playerIndex)
            }
        }, 1200)
    }

    /**
     * 获取对手手牌数（用于AI决策）
     */
    private fun getOpponentCardCounts(myIndex: Int): IntArray {
        return intArrayOf(
            players[(myIndex + 1) % 3].cardCount,
            players[(myIndex + 2) % 3].cardCount
        )
    }

    /**
     * 获取AI提示的出牌建议（给人类玩家用）
     */
    fun getHint(): List<Card>? {
        val hand = players[0].handCards
        val lastPlay = stateMachine.lastPlayedGroup
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        // 返回第一个合法组合
        return validPlays.firstOrNull()?.cards
    }
}
