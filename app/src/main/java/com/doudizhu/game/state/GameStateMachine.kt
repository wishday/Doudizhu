package com.doudizhu.game.state

/**
 * 游戏状态枚举
 * 定义游戏流程中的所有阶段
 */
enum class GamePhase {
    MENU,           // 主界面（难度选择）
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
    var phase: GamePhase = GamePhase.MENU
        private set

    /** 当前操作的玩家索引（0/1/2） */
    var currentPlayerIndex: Int = 0

    /** 当前最高叫分 */
    var currentMaxBid: Int = 0

    /** 当前最高叫分的玩家索引 */
    var maxBidPlayerIndex: Int = -1

    /** 叫分轮次计数 */
    var bidRoundCount: Int = 0

    /**
     * 本轮叫分是否已因「有人直接叫3分」而提前结束（阶段结束后仍保留，直到下轮 startBidding / startDealing 复位）。
     * 引擎与 UI 据此走「叫3分」专属节拍：公布该玩家的「叫3分」后保持展示一段时间再弹「xx成为地主」，
     * **不补发**其他未行动玩家的默认叫分（未行动者在屏幕上保持留白）。
     */
    var biddingEndedByThreeBid: Boolean = false
        private set

    /**
     * 本轮中实际完成叫分动作的玩家索引集合（每次 processBid 记录一位）。
     * 叫3分提前结束时 bidRoundCount 已被置满 3，不能再用于推断"谁已行动"；
     * UI 改读本集合作为权威数据，精确标记已行动玩家、其余留白。
     * CopyOnWrite：绘制/UI 线程读取快照与引擎线程写入并发安全。
     */
    val actedBidPlayers: java.util.concurrent.CopyOnWriteArraySet<Int> =
        java.util.concurrent.CopyOnWriteArraySet()

    /** 首位叫分玩家索引（叫分顺序为 firstBidderIndex → +1 → +2，UI 据此推断各人已行动状态） */
    var firstBidderIndex: Int = 0

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

    /** 本局炸弹/火箭次数（每个使结算倍数 ×2） */
    var bombCount: Int = 0

    /** 出牌阶段：地主出牌手数（用于判定反春天） */
    var landlordPlayCount: Int = 0

    /** 出牌阶段：农民出牌手数（用于判定春天） */
    var farmerPlayCount: Int = 0

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
        biddingEndedByThreeBid = false
        actedBidPlayers.clear()
        firstBidderIndex = 0
        hasLandlord = false
        playHistory.clear()
        bombCount = 0
        landlordPlayCount = 0
        farmerPlayCount = 0
    }

    /**
     * 进入主界面（难度选择）：清空本局残留状态（底牌、出牌记录等），避免主界面残留上局底牌
     */
    fun startMenu() {
        phase = GamePhase.MENU
        bottomCards.clear()
        playHistory.clear()
        lastPlayedGroup = null
        lastPlayedPlayerIndex = -1
        landlordIndex = -1
        hasLandlord = false
    }

    /**
     * 转换到叫地主阶段
     * @param firstBidPlayer 第一个叫分的玩家索引
     */
    fun startBidding(firstBidPlayer: Int) {
        phase = GamePhase.BIDDING
        currentPlayerIndex = firstBidPlayer
        firstBidderIndex = firstBidPlayer
        biddingEndedByThreeBid = false
        actedBidPlayers.clear()
    }

    /**
     * 处理叫分
     * @param playerIndex 叫分玩家
     * @param score 叫分值（0=不叫，1~3=叫分）
     * @return true表示叫地主阶段结束
     */
    fun processBid(playerIndex: Int, score: Int): Boolean {
        // 叫分必须高于当前最高分，否则视为不叫（防止越叫越低）
        val realScore = computeRealBid(score)
        if (realScore > 0) {
            currentMaxBid = realScore
            maxBidPlayerIndex = playerIndex
        }

        bidRoundCount++
        actedBidPlayers.add(playerIndex)

        // 如果有人叫3分，直接结束：其余玩家不再行动（屏幕上保持留白、不补发默认叫分），
        // 引擎据 biddingEndedByThreeBid 把这位的「叫3分」结果保持展示一段时间后再弹「xx成为地主」
        if (realScore == 3) {
            bidRoundCount = 3
            biddingEndedByThreeBid = true
            return finalizeBidding()
        }

        // 三个人都叫过了
        if (bidRoundCount >= 3) {
            return finalizeBidding()
        }

        // 下一个玩家
        currentPlayerIndex = (playerIndex + 1) % 3
        return false
    }

    /**
     * 计算有效叫分：叫分必须高于当前最高分，非法叫分（≤当前最高分）视为不叫(0)
     * 供状态机与引擎共用，避免两处规则不一致
     */
    fun computeRealBid(score: Int): Int =
        if (score > 0 && score <= currentMaxBid) 0 else score

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
        // 统计地主/农民出牌手数，用于判定春天/反春天
        if (playerIndex == landlordIndex) landlordPlayCount++ else farmerPlayCount++
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
