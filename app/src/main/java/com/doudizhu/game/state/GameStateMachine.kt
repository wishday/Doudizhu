package com.doudizhu.game.state

/**
 * 游戏状态枚举
 * 定义游戏流程中的所有阶段
 */
enum class GamePhase {
    DEALING,        // 发牌中
    BIDDING,        // 叫地主阶段
    PLAYING,        // 出牌阶段
    SETTLING,       // 结算阶段
    GAME_OVER       // 游戏结束
}

/**
 * 游戏状态机
 * 管理游戏流程的状态转换
 */
class GameStateMachine {
    /** 当前游戏阶段 */
    var phase: GamePhase = GamePhase.DEALING
        private set

    /** 当前操作的玩家索引（0/1/2） */
    var currentPlayerIndex: Int = 0

    /** 叫地主阶段的当前叫分玩家 */
    var biddingPlayerIndex: Int = 0

    /** 当前最高叫分 */
    var currentMaxBid: Int = 0

    /** 当前最高叫分的玩家索引 */
    var maxBidPlayerIndex: Int = -1

    /** 叫分轮次计数 */
    var bidRoundCount: Int = 0

    /** 上一手出牌的牌组信息 */
    var lastPlayedGroup: com.doudizhu.game.model.CardGroup? = null

    /** 上一手出牌的玩家索引 */
    var lastPlayedPlayerIndex: Int = -1

    /** 连续不出的人数 */
    var passCount: Int = 0

    /** 底牌（3张）（CopyOnWrite：绘制线程与UI线程并发读写安全） */
    val bottomCards: MutableList<com.doudizhu.game.model.Card> = java.util.concurrent.CopyOnWriteArrayList()

    /** 地主玩家索引 */
    var landlordIndex: Int = -1

    /** 地主出牌记录 */
    val playHistory: MutableList<Pair<Int, List<com.doudizhu.game.model.Card>>> = mutableListOf()

    /** 是否有人叫了地主 */
    var hasLandlord: Boolean = false

    /** 本局底分（叫地主最终分数） */
    var currentBidScore: Int = 0

    /** 本局炸弹/火箭次数（不计入倍数） */
    var bombCount: Int = 0

    /**
     * 转换到发牌阶段
     */
    fun startDealing() {
        phase = GamePhase.DEALING
        currentPlayerIndex = 0
        lastPlayedGroup = null
        lastPlayedPlayerIndex = -1
        passCount = 0
        bottomCards.clear()
        landlordIndex = -1
        currentMaxBid = 0
        maxBidPlayerIndex = -1
        bidRoundCount = 0
        hasLandlord = false
        playHistory.clear()
        bombCount = 0
    }

    /**
     * 转换到叫地主阶段
     * @param firstBidPlayer 第一个叫分的玩家索引
     */
    fun startBidding(firstBidPlayer: Int) {
        phase = GamePhase.BIDDING
        biddingPlayerIndex = firstBidPlayer
        currentPlayerIndex = firstBidPlayer
    }

    /**
     * 处理叫分
     * @param playerIndex 叫分玩家
     * @param score 叫分值（0=不叫，1~3=叫分）
     * @return true表示叫地主阶段结束
     */
    fun processBid(playerIndex: Int, score: Int): Boolean {
        // 叫分必须高于当前最高分，否则视为不叫（防止越叫越低）
        val realScore = if (score > 0 && score <= currentMaxBid) 0 else score
        if (realScore > 0) {
            currentMaxBid = realScore
            maxBidPlayerIndex = playerIndex
        }
        bidRoundCount++

        // 如果有人叫3分，直接结束
        if (realScore == 3) {
            return finalizeBidding()
        }

        // 三个人都叫过了
        if (bidRoundCount >= 3) {
            return finalizeBidding()
        }

        // 下一个玩家
        currentPlayerIndex = (playerIndex + 1) % 3
        biddingPlayerIndex = currentPlayerIndex
        return false
    }

    /**
     * 结束叫地主阶段
     */
    private fun finalizeBidding(): Boolean {
        if (maxBidPlayerIndex >= 0) {
            landlordIndex = maxBidPlayerIndex
            hasLandlord = true
        }
        return true
    }

    /**
     * 转换到出牌阶段
     */
    fun startPlaying() {
        phase = GamePhase.PLAYING
        currentPlayerIndex = landlordIndex
        lastPlayedGroup = null
        lastPlayedPlayerIndex = -1
        passCount = 0
    }

    /**
     * 处理出牌
     * @param playerIndex 出牌玩家
     * @param cards 出的牌
     * @return 下一个出牌的玩家索引
     */
    fun processPlay(playerIndex: Int, cards: com.doudizhu.game.model.CardGroup): Int {
        lastPlayedGroup = cards
        lastPlayedPlayerIndex = playerIndex
        passCount = 0
        // 统计炸弹/火箭，用于结算倍数
        if (cards.type == com.doudizhu.game.model.CardType.BOMB ||
            cards.type == com.doudizhu.game.model.CardType.ROCKET) {
            bombCount++
        }
        playHistory.add(Pair(playerIndex, cards.cards))
        return nextPlayer(playerIndex)
    }

    /**
     * 处理不出（过牌）
     * @param playerIndex 过牌玩家
     * @return 下一个出牌的玩家索引，如果新一轮开始则lastPlayedGroup会被清空
     */
    fun processPass(playerIndex: Int): Int {
        passCount++
        // 如果其他两人都过了，新一轮开始
        if (passCount >= 2) {
            lastPlayedGroup = null
            lastPlayedPlayerIndex = -1
            passCount = 0
        }
        return nextPlayer(playerIndex)
    }

    /**
     * 获取下一个玩家索引
     */
    private fun nextPlayer(current: Int): Int {
        return (current + 1) % 3
    }

    /**
     * 检查游戏是否结束
     * @param playerCards 各玩家手牌数量列表
     * @return 胜利玩家索引，-1表示未结束
     */
    fun checkGameOver(playerCards: List<Int>): Int {
        for (i in playerCards.indices) {
            if (playerCards[i] == 0) return i
        }
        return -1
    }

    /**
     * 转换到结算阶段
     */
    fun startSettling() {
        phase = GamePhase.SETTLING
    }

    /**
     * 转换到游戏结束
     */
    fun endGame() {
        phase = GamePhase.GAME_OVER
    }

    /**
     * 判断当前玩家是否需要必须出牌（不能过）
     * 自由出牌时必须出牌
     */
    fun mustPlay(): Boolean {
        return lastPlayedGroup == null || lastPlayedPlayerIndex == currentPlayerIndex
    }
}
