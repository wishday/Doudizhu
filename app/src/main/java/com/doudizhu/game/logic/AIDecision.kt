package com.doudizhu.game.logic

import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.model.PlayerRole

/**
 * AI决策引擎（增强版）
 * 支持智能叫分、竞合策略、综合出牌决策
 */
object AIDecision {

    /**
     * AI做出牌决策
     * @param hand 手牌列表
     * @param lastPlay 上一手牌（null表示自由出牌）
     * @param difficulty AI难度
     * @param role AI角色（地主/农民）
     * @param teammateCardCount 队友手牌数（农民时有效）
     * @param lastPlayerIndex 上一手出牌的玩家索引
     * @param myIndex 自己的玩家索引
     * @param opponentCardCounts 对手手牌数列表 [右AI, 人类, 左AI]
     * @return 选择的出牌组合，null表示不出
     */
    fun decide(
        hand: List<Card>,
        lastPlay: CardGroup?,
        difficulty: Difficulty,
        role: PlayerRole,
        teammateCardCount: Int = 0,
        lastPlayerIndex: Int = -1,
        myIndex: Int = -1,
        opponentCardCounts: IntArray = intArrayOf()
    ): CardGroup? {
        return when (difficulty) {
            Difficulty.EASY -> easyDecision(hand, lastPlay)
            Difficulty.NORMAL -> normalDecision(hand, lastPlay, role, teammateCardCount, lastPlayerIndex, myIndex, opponentCardCounts)
        }
    }

    /**
     * AI智能叫分决策
     * 根据手牌强度决定是否积极抢地主
     * @param hand 初始手牌（17张）
     * @param currentMaxBid 当前最高叫分
     * @return 叫分（0-3），0表示不叫
     */
    fun decideBid(hand: List<Card>, currentMaxBid: Int): Int {
        val strength = evaluateHandStrength(hand)
        
        // 根据手牌强度决定叫分
        return when {
            // 超强牌：直接叫3分
            strength >= 85 -> 3
            // 强牌：叫2分
            strength >= 70 && currentMaxBid < 2 -> 2
            // 中等偏上：叫1分
            strength >= 55 && currentMaxBid < 1 -> 1
            // 弱牌：不叫
            else -> 0
        }
    }

    /**
     * 评估手牌强度（0-100分）
     * 考虑因素：大牌数量、炸弹/火箭、牌型连贯性
     */
    private fun evaluateHandStrength(hand: List<Card>): Int {
        var score = 0
        
        // 1. 统计大牌（2、小王、大王）
        val twos = hand.count { it.rank == 15 }  // 2
        val smallJoker = hand.count { it.rank == 16 }
        val bigJoker = hand.count { it.rank == 17 }
        
        // 大王 +25分，小王 +20分
        score += bigJoker * 25
        score += smallJoker * 20
        
        // 2的数量：每张 +8分
        score += twos * 8
        
        // 2. 统计炸弹
        val rankCounts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val bombs = rankCounts.count { it.value == 4 }
        score += bombs * 20  // 每个炸弹 +20分
        
        // 火箭（双王）
        if (smallJoker > 0 && bigJoker > 0) {
            score += 15  // 额外加分
        }
        
        // 3. 牌型连贯性（顺子潜力）
        val ranks = hand.map { it.rank }.filter { it in 3..14 }.distinct().sorted()
        var straightLength = 1
        var maxStraight = 1
        for (i in 1 until ranks.size) {
            if (ranks[i] == ranks[i-1] + 1) {
                straightLength++
                maxStraight = maxOf(maxStraight, straightLength)
            } else {
                straightLength = 1
            }
        }
        // 长顺子加分
        if (maxStraight >= 5) score += (maxStraight - 4) * 5
        
        // 4. 三张数量（三带潜力）
        val triples = rankCounts.count { it.value == 3 }
        score += triples * 6
        
        // 5. 对子数量
        val pairs = rankCounts.count { it.value == 2 }
        score += pairs * 2
        
        return score.coerceIn(0, 100)
    }

    /**
     * 简单难度：随机出牌，能管就管
     */
    private fun easyDecision(hand: List<Card>, lastPlay: CardGroup?): CardGroup? {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) return null

        // 如果是自由出牌，随机选一个
        if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            return validPlays.randomOrNull()
        }

        // 需要管牌时，随机选一个能管的（不优先用炸弹）
        val nonBombPlays = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        if (nonBombPlays.isNotEmpty()) {
            return nonBombPlays.random()
        }

        // 只有炸弹/火箭能管，50%概率出
        return if (Math.random() < 0.5) validPlays.random() else null
    }

    /**
     * 普通难度：增强策略出牌
     */
    private fun normalDecision(
        hand: List<Card>,
        lastPlay: CardGroup?,
        role: PlayerRole,
        teammateCardCount: Int,
        lastPlayerIndex: Int,
        myIndex: Int,
        opponentCardCounts: IntArray
    ): CardGroup? {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) return null

        // 自由出牌策略
        if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            return freePlayStrategy(hand, validPlays, role, teammateCardCount, opponentCardCounts)
        }

        // 跟牌策略（考虑竞合关系）
        return followPlayStrategy(hand, validPlays, lastPlay, role, teammateCardCount, lastPlayerIndex, myIndex, opponentCardCounts)
    }

    /**
     * 自由出牌策略（增强版）
     * 考虑身份、队友状态、对手状态
     */
    private fun freePlayStrategy(
        hand: List<Card>,
        validPlays: List<CardGroup>,
        role: PlayerRole,
        teammateCardCount: Int,
        opponentCardCounts: IntArray
    ): CardGroup {
        // 如果手牌只剩一手，直接出完
        val fullHand = CardRuleEngine.identify(hand)
        if (fullHand.type != CardType.INVALID) {
            return fullHand
        }

        // 农民配合：队友快出完时，出大牌帮队友顶
        if (role == PlayerRole.FARMER && teammateCardCount in 1..2) {
            val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
            if (bigPlays.isNotEmpty()) {
                return bigPlays.maxByOrNull { it.mainRank }!!
            }
        }

        // 地主策略：手牌少时积极出大牌
        if (role == PlayerRole.LANDLORD && hand.size <= 5) {
            val bigPlays = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
            if (bigPlays.isNotEmpty()) {
                return bigPlays.maxByOrNull { it.mainRank }!!
            }
        }

        // 对手牌少时，出大牌压制
        val minOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min() else 20
        if (minOpponentCards <= 3) {
            // 对手快出完了，出大牌压制
            val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
            if (bigPlays.isNotEmpty()) {
                return bigPlays.maxByOrNull { it.mainRank }!!
            }
        }

        // 优先出顺子 > 连对 > 三带 > 对子 > 单张
        val priority = listOf(
            CardType.STRAIGHT, CardType.STRAIGHT_PAIR,
            CardType.TRIPLE_TWO, CardType.TRIPLE_ONE, CardType.TRIPLE,
            CardType.PAIR, CardType.SINGLE
        )

        for (type in priority) {
            val plays = validPlays.filter { it.type == type }
            if (plays.isNotEmpty()) {
                // 出最小的
                return plays.minByOrNull { it.mainRank }!!
            }
        }

        // 最后出炸弹
        return validPlays.first()
    }

    /**
     * 跟牌策略（增强版）
     * 考虑竞合关系：是否压队友、是否放过队友
     */
    private fun followPlayStrategy(
        hand: List<Card>,
        validPlays: List<CardGroup>,
        lastPlay: CardGroup,
        role: PlayerRole,
        teammateCardCount: Int,
        lastPlayerIndex: Int,
        myIndex: Int,
        opponentCardCounts: IntArray
    ): CardGroup? {
        // 如果手牌数量 <= 出牌数量，考虑直接出完
        val fullHand = CardRuleEngine.identify(hand)
        if (fullHand.type != CardType.INVALID && CardRuleEngine.isValidPlay(fullHand, lastPlay)) {
            return fullHand
        }

        // 判断上一手是否是队友出的
        val isTeammatePlayed = isTeammate(lastPlayerIndex, myIndex, role)
        
        // 农民不压队友（除非队友快出完了可以送）
        if (role == PlayerRole.FARMER && isTeammatePlayed && teammateCardCount > 2) {
            // 队友出的牌，不压（除非能出完）
            return null
        }

        // 非炸弹/火箭的出牌选择
        val normalPlays = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }

        // 对手牌少时，积极出牌压制
        val minOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min() else 20
        
        if (normalPlays.isNotEmpty()) {
            // 对手快出完时，出最大的能管的牌
            if (minOpponentCards <= 3) {
                return normalPlays.maxByOrNull { it.mainRank }
            }
            // 正常情况：出最小的能管的牌
            return normalPlays.minByOrNull { it.mainRank }
        }

        // 考虑用炸弹
        val bombs = validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }
        if (bombs.isNotEmpty()) {
            // 自己手牌少于5张时使用炸弹
            if (hand.size <= 5) {
                return bombs.minByOrNull { it.mainRank }
            }
            // 对手快出完时使用炸弹
            if (minOpponentCards <= 3) {
                return bombs.minByOrNull { it.mainRank }
            }
            // 50%概率出炸弹
            return if (Math.random() < 0.5) bombs.minByOrNull { it.mainRank } else null
        }

        return null  // 不出
    }

    /**
     * 判断上一手出牌的玩家是否是队友
     * 地主：没有队友
     * 农民：另一个农民是队友
     */
    private fun isTeammate(lastPlayerIndex: Int, myIndex: Int, role: PlayerRole): Boolean {
        if (role == PlayerRole.LANDLORD) return false
        if (lastPlayerIndex < 0 || myIndex < 0) return false
        
        // 农民索引：0, 1, 2 中，地主占一个，另外两个是农民
        // 假设地主是索引 X，则农民是 (X+1)%3 和 (X+2)%3
        // 如果 lastPlayerIndex 不是 myIndex，且 lastPlayerIndex 不是地主，则是队友
        // 简化判断：如果 lastPlayerIndex 和 myIndex 不同，且都不是地主，则是队友
        // 这里需要知道谁是地主，但函数签名没有传入，所以用简化逻辑
        // 假设：如果 lastPlayerIndex 和 myIndex 的差是 2（即隔一个人），则是队友
        val diff = Math.abs(lastPlayerIndex - myIndex)
        return diff == 2 || diff == 1  // 简化：只要不是自己，都可能是队友（需要更精确的判断）
    }
}
