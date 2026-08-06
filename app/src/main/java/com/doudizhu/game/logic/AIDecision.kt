package com.doudizhu.game.logic

import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.model.PlayerRole

/**
 * AI决策引擎（重写版）
 *
 * 核心出牌哲学：
 *  1. 永远先从最小的牌开始打（小牌诱饵），大牌（2/王/炸弹）只用来「回收控制权」
 *  2. 跟牌永远用「恰好能压住对手」的最小牌，绝不浪费大牌
 *  3. 形成「小牌 - 对手压 - 大牌回收 - 再走小牌」的节奏，杜绝「从大往小打白送大牌」
 *  4. 手牌结构规划：尽量一次甩掉多张结构牌（顺子/连对/飞机），减少总手数
 *  5. 炸弹纪律：只在能确保胜利或阻止对手立即获胜时才使用
 */
object AIDecision {

    // ---- 每轮上下文（decide() 开头写入，供内部函数访问）----
    private var ctxRole: PlayerRole = PlayerRole.FARMER
    private var ctxTeammateCardCount: Int = 0
    private var ctxMinOpponentCards: Int = 20
    private var ctxMaxOpponentCards: Int = 20
    private var ctxUnseenCounts: IntArray = IntArray(18)
    private var ctxPerPlayerPlayed: Array<IntArray> = Array(3) { IntArray(18) }
    private var ctxPrimaryOpponent: Int = -1
    private var ctxTeammateIndex: Int = -1

    /** 控牌：2(15)/小王(16)/大王(17)，用于回收控制权，尽量后手使用 */
    private val ControlRanks = setOf(15, 16, 17)

    private fun isControlRank(rank: Int): Boolean = rank in ControlRanks

    private fun isUrgent(): Boolean =
        ctxMinOpponentCards <= 3 || ctxTeammateCardCount in 1..2

    /**
     * AI做出牌决策
     * @param hand 手牌列表
     * @param lastPlay 上一手牌（null表示自由出牌）
     * @param difficulty AI难度
     * @param role AI角色（地主/农民）
     * @param teammateCardCount 队友手牌数（农民时有效）
     * @param lastPlayerIndex 上一次出牌的玩家索引
     * @param myIndex 自己的玩家索引
     * @param opponentCardCounts 对手手牌数列表
     * @param landlordIndex 地主玩家索引（用于判断队友）
     * @param unseenCounts 各rank在对手手中的剩余张数（长度18，下标即rank）
     * @param perPlayerPlayed 各玩家已打出的各 rank 张数
     * @param primaryOpponentIndex 主要对手索引
     * @param teammateIndex 队友索引（地主=-1）
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
        opponentCardCounts: IntArray = intArrayOf(),
        landlordIndex: Int = -1,
        unseenCounts: IntArray = intArrayOf(),
        perPlayerPlayed: Array<IntArray> = Array(3) { IntArray(18) },
        primaryOpponentIndex: Int = -1,
        teammateIndex: Int = -1
    ): CardGroup? {
        ctxRole = role
        ctxTeammateCardCount = teammateCardCount
        ctxMinOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min()!! else 20
        ctxMaxOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.max()!! else 20
        ctxUnseenCounts = unseenCounts
        ctxPerPlayerPlayed = perPlayerPlayed
        ctxPrimaryOpponent = primaryOpponentIndex
        ctxTeammateIndex = teammateIndex

        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            (myIndex >= 0 && lastPlayerIndex == myIndex)

        return when (difficulty) {
            Difficulty.EASY -> if (isFreeLead) easyFreeLead(hand) else easyFollow(hand, lastPlay)
            Difficulty.NORMAL -> if (isFreeLead) {
                normalFreeLead(hand, role, myIndex, landlordIndex)
            } else {
                normalFollow(hand, lastPlay!!, role, lastPlayerIndex, myIndex, landlordIndex)
            }
        }
    }

    // ==================== 简单难度 ====================

    private fun easyFreeLead(hand: List<Card>): CardGroup? {
        val plays = CardRuleEngine.findAllValidPlays(hand, null)
        val singles = plays.filter { it.type == CardType.SINGLE }
        if (singles.isNotEmpty()) return singles.minByOrNull { it.mainRank }
        val pairs = plays.filter { it.type == CardType.PAIR }
        if (pairs.isNotEmpty()) return pairs.minByOrNull { it.mainRank }
        return plays.randomOrNull()
    }

    private fun easyFollow(hand: List<Card>, lastPlay: CardGroup?): CardGroup? {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) return null
        if (lastPlay == null || lastPlay.type == CardType.INVALID) return easyFreeLead(hand)
        val nonBomb = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        if (nonBomb.isNotEmpty() && Math.random() < 0.7) return nonBomb.minByOrNull { it.mainRank }
        val bombs = validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }
        if (bombs.isNotEmpty() && Math.random() < 0.4) return bombs.minByOrNull { it.mainRank }
        return null
    }

    // ==================== 叫分 ====================

    fun decideBid(hand: List<Card>, currentMaxBid: Int, difficulty: Difficulty): Int {
        val strength = evaluateHandStrength(hand) + bottomCardBonus(hand) + (0..2).random()
        val escalation = if (currentMaxBid >= 2) 6 else 0

        return when (difficulty) {
            Difficulty.EASY -> when {
                strength >= 85 + escalation -> 3
                strength >= 70 + escalation && currentMaxBid < 2 -> 2
                strength >= 58 + escalation && currentMaxBid < 1 -> 1
                else -> 0
            }
            Difficulty.NORMAL -> when {
                strength >= 72 + escalation -> 3
                strength >= 58 + escalation && currentMaxBid < 2 -> 2
                strength >= 46 + escalation && currentMaxBid < 1 -> 1
                else -> 0
            }
        }
    }

    private fun evaluateHandStrength(hand: List<Card>): Int {
        var score = 0
        val aces = hand.count { it.rank == 14 }
        val twos = hand.count { it.rank == 15 }
        val smallJoker = hand.count { it.rank == 16 }
        val bigJoker = hand.count { it.rank == 17 }

        score += bigJoker * 25
        score += smallJoker * 20
        score += twos * 8
        score += aces * 4

        val rankCounts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val bombs = rankCounts.count { it.value == 4 }
        score += bombs * 22
        if (smallJoker > 0 && bigJoker > 0) score += 15

        val ranks = hand.map { it.rank }.filter { it in 3..14 }.distinct().sorted()
        var straightLength = 1
        var maxStraight = 1
        for (i in 1 until ranks.size) {
            if (ranks[i] == ranks[i - 1] + 1) {
                straightLength++
                maxStraight = maxOf(maxStraight, straightLength)
            } else {
                straightLength = 1
            }
        }
        if (maxStraight >= 5) score += (maxStraight - 4) * 5

        val pairRanks = rankCounts.filterValues { it >= 2 }.keys.filter { it in 3..14 }.sorted()
        var i = 0
        while (i < pairRanks.size) {
            var j = i
            while (j + 1 < pairRanks.size && pairRanks[j + 1] == pairRanks[j] + 1) j++
            val runLen = j - i + 1
            if (runLen >= 3) score += 6 + (runLen - 3) * 3
            i = j + 1
        }

        val tripleRanks = rankCounts.filterValues { it >= 3 }.keys.filter { it in 3..14 }.sorted()
        i = 0
        while (i < tripleRanks.size) {
            var j = i
            while (j + 1 < tripleRanks.size && tripleRanks[j + 1] == tripleRanks[j] + 1) j++
            if (j - i + 1 >= 2) score += 10
            i = j + 1
        }

        score += rankCounts.count { it.value == 3 } * 6
        score += rankCounts.count { it.value == 2 } * 2

        val turns = estimateHandTurns(hand)
        score += (20 - turns * 2).coerceAtLeast(0)

        val controlsAboveA = twos + smallJoker + bigJoker
        if (controlsAboveA == 0 && aces == 0 && bombs == 0) score -= 14
        else if (controlsAboveA == 0 && aces == 0) score -= 7
        else if (controlsAboveA == 0) score -= 4

        return score.coerceIn(0, 100)
    }

    private fun bottomCardBonus(hand: List<Card>): Int {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val tripCount = counts.count { it.value == 3 }
        return 3 + tripCount * 2
    }

    // ==================== 自由出牌（领出）====================

    private fun normalFreeLead(
        hand: List<Card>,
        role: PlayerRole,
        myIndex: Int,
        landlordIndex: Int
    ): CardGroup {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, null)

        // 0. 一手能出完，直接出完
        val fullHand = CardRuleEngine.identify(hand)
        if (fullHand.type != CardType.INVALID) return fullHand

        // 1. 队友接近出完：优先喂牌（走最小，让队友接走）
        if (role == PlayerRole.FARMER && ctxTeammateCardCount in 1..2) {
            val feed = feedTeammate(hand)
            if (feed != null) return feed
        }

        // 2. 残局必胜：能确保冲完就直接冲
        canFinishGuaranteed(hand, validPlays)?.let { return it }

        // 3. 手牌少：残局枚举，最小剩余手数 + 防喂杀
        if (hand.size <= 5) return endgameLead(hand, validPlays)

        // 4. 常规领出：小牌先行
        return chooseLead(hand, validPlays)
    }

    /** 队友剩1~2张时的喂牌：出最小的非控单张/对子，让队友好接走 */
    private fun feedTeammate(hand: List<Card>): CardGroup? {
        val plan = buildPlan(hand)
        if (ctxTeammateCardCount == 1) {
            val single = plan.firstOrNull { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
                ?: plan.firstOrNull { it.type == CardType.SINGLE }
            if (single != null && feedDanger(single) <= 1) return single
            return null
        }
        // 剩2张：优先对子（队友是对子可直接接走），其次单张
        val pair = plan.firstOrNull { it.type == CardType.PAIR && !isControlRank(it.mainRank) }
        if (pair != null && feedDanger(pair) <= 2) return pair
        val single = plan.firstOrNull { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
        if (single != null && feedDanger(single) <= 2) return single
        return null
    }

    /** 残局领出（手牌 <= 5）：选「对手最难压住 + 剩余手数最少」的一手 */
    private fun endgameLead(hand: List<Card>, validPlays: List<CardGroup>): CardGroup {
        val candidates = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        val best = candidates.minWithOrNull(
            compareBy(
                { feedDanger(it) },
                { estimateHandTurns(hand.filter { c -> it.cards.none { x -> x.id == c.id } }) },
                { it.mainRank }
            )
        )
        return best ?: validPlays.first()
    }

    /**
     * 常规领出选择：
     *  A. 对手报单（1张）：优先甩结构/对子（防被接），只能出单张时出最大
     *  B. 甩结构牌（顺子/连对/飞机/三带）：一次减负多张，先甩威胁低的
     *  C. 出最小非控单张/对子作诱饵，大牌后手回收
     */
    private fun chooseLead(hand: List<Card>, validPlays: List<CardGroup>): CardGroup {
        val plan = buildPlan(hand)

        // A. 对手报单：绝不能出能被接走的单张
        if (ctxMinOpponentCards == 1) {
            for (g in plan) {
                if (isStructure(g) && g.type != CardType.SINGLE && feedDanger(g) == 0) return g
            }
            val pair = plan.firstOrNull { it.type == CardType.PAIR }
            if (pair != null) return pair
            val single = plan.firstOrNull { it.type == CardType.SINGLE }
            if (single != null) return single // 只能出单张，出最小（对手只剩一张若比我大也没办法）
            return validPlays.first()
        }

        // B. 结构牌优先：甩掉多张垃圾，先甩威胁最低的
        val structures = plan.filter { isStructure(it) }
        if (structures.isNotEmpty()) {
            return structures.minWithOrNull(compareBy({ feedDanger(it) }, { it.mainRank }))!!
        }

        // C. 最小非控单张/对子作诱饵（保留2/王后手回收）
        val bait = plan.filter {
            (it.type == CardType.SINGLE || it.type == CardType.PAIR) && !isControlRank(it.mainRank)
        }
        if (bait.isNotEmpty()) {
            return bait.minWithOrNull(compareBy({ it.mainRank }, { if (it.type == CardType.PAIR) 1 else 0 }))!!
        }

        // D. 兜底：最小的可出牌（可能是控牌）
        val nonBomb = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        return nonBomb.minByOrNull { it.mainRank } ?: validPlays.first()
    }

    private fun isStructure(g: CardGroup): Boolean = when (g.type) {
        CardType.STRAIGHT, CardType.STRAIGHT_PAIR, CardType.PLANE, CardType.PLANE_SINGLE,
        CardType.PLANE_PAIR, CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO -> true
        else -> false
    }

    // ==================== 跟牌 ====================

    private fun normalFollow(
        hand: List<Card>,
        lastPlay: CardGroup,
        role: PlayerRole,
        lastPlayerIndex: Int,
        myIndex: Int,
        landlordIndex: Int
    ): CardGroup? {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) return null

        // 0. 一手能出完直接出完
        val fullHand = CardRuleEngine.identify(hand)
        if (fullHand.type != CardType.INVALID && CardRuleEngine.isValidPlay(fullHand, lastPlay)) {
            return fullHand
        }

        // 1. 队友出的牌：农民绝不压队友
        if (role == PlayerRole.FARMER && isTeammate(lastPlayerIndex, myIndex, role, landlordIndex)) {
            return null
        }

        val sameType = validPlays.filter { it.type == lastPlay.type }
        val nonControl = sameType.filter { !isControlRank(it.mainRank) }
        val teammateIsNext = role == PlayerRole.FARMER && myIndex >= 0 && (myIndex + 1) % 3 == ctxTeammateIndex
        val landlordPlayed = role == PlayerRole.FARMER && lastPlayerIndex == landlordIndex
        val myLastResponder = landlordPlayed && !teammateIsNext

        // 2. 对手快赢了，必须压住
        if (ctxMinOpponentCards <= 2) {
            if (ctxMinOpponentCards == 1 &&
                (lastPlay.type == CardType.SINGLE || lastPlay.type == CardType.PAIR)) {
                // 对手报单：用最大牌压，防止对手回手赢走
                val maxPlay = sameType.maxByOrNull { it.mainRank }
                if (maxPlay != null) return maxPlay
            } else {
                // 对手剩2张：用最小能压的（尽量非控牌）
                val minPlay = nonControl.minByOrNull { it.mainRank } ?: sameType.minByOrNull { it.mainRank }
                if (minPlay != null) return minPlay
            }
            // 无普通牌可压时用炸弹阻止
            return decideBomb(validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }, hand)
        }

        // 3. 我是最后一个响应地主的人（队友已过牌）→ 尽量接过控制，但优先最小
        if (myLastResponder) {
            if (nonControl.isNotEmpty()) return nonControl.minByOrNull { it.mainRank }
            if (sameType.isNotEmpty() && isUrgent()) return sameType.minByOrNull { it.mainRank }
            // 非紧急：放走地主，保存控牌
        }

        // 4. 普通情况：最小能压的非控牌（核心：绝不浪费大牌）
        if (nonControl.isNotEmpty()) return nonControl.minByOrNull { it.mainRank }

        // 5. 只剩控牌可跟：非紧急保存控牌
        if (sameType.isNotEmpty()) {
            if (isUrgent() || hand.size <= 4) return sameType.minByOrNull { it.mainRank }
            return null
        }

        // 6. 炸弹：紧急时才炸
        if (isUrgent()) {
            return decideBomb(validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }, hand)
        }
        return null
    }

    private fun isTeammate(lastPlayerIndex: Int, myIndex: Int, role: PlayerRole, landlordIndex: Int): Boolean {
        if (role == PlayerRole.LANDLORD) return false
        if (lastPlayerIndex < 0 || myIndex < 0) return false
        if (lastPlayerIndex == myIndex) return false
        return lastPlayerIndex != landlordIndex
    }

    // ==================== 炸弹纪律 ====================

    private fun decideBomb(bombs: List<CardGroup>, hand: List<Card>): CardGroup? {
        val b = bombs.maxByOrNull { it.mainRank } ?: return null
        val canBeCountered = hasUnseenHigherBombOrRocket(b.mainRank)
        // 1. 对手距获胜很近：必须炸阻止
        if (ctxMinOpponentCards <= 2) return b
        // 2. 出炸后剩余牌可在一两手内收完：炸
        val remaining = hand.filter { c -> b.cards.none { it.id == c.id } }
        if (!canBeCountered && remaining.isNotEmpty() && canFinishRemaining(remaining)) return b
        // 3. 对手无任何2/王且无反制炸弹：安全翻倍炸
        val controls = (15..17).sumOf { r -> if (r < ctxUnseenCounts.size) ctxUnseenCounts[r] else 0 }
        if (controls == 0 && !canBeCountered) return b
        // 其余情况保存炸弹
        return null
    }

    private fun canFinishRemaining(remaining: List<Card>): Boolean {
        if (remaining.isEmpty()) return true
        if (remaining.size > 8) return false
        return estimateHandTurns(remaining) <= 2
    }

    // ==================== 必胜判定 ====================

    /**
     * 残局必胜：对手手数 <= 4、我方手牌 <= 12、无 unseen 炸弹时，
     * 枚举领出，若能清空手牌或「绝对通吃并持续控场收完」则必胜
     */
    private fun canFinishGuaranteed(hand: List<Card>, validPlays: List<CardGroup>): CardGroup? {
        if (ctxMinOpponentCards > 4 || hand.size > 12) return null
        if (hasUnseenBombOrRocket()) return null
        val candidates = validPlays
            .filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
            .sortedBy { feedDanger(it) }
        for (c in candidates) {
            val remaining = hand.filter { card -> c.cards.none { it.id == card.id } }
            if (remaining.isEmpty()) return c
            if (isUnbeatable(c) && canWinInControl(remaining, 3)) return c
        }
        return null
    }

    private fun canWinInControl(hand: List<Card>, depth: Int): Boolean {
        if (hand.isEmpty()) return true
        if (depth <= 0) return false
        val plays = CardRuleEngine.findAllValidPlays(hand, null)
            .filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        for (c in plays) {
            if (!isUnbeatable(c)) continue
            val remaining = hand.filter { card -> c.cards.none { it.id == card.id } }
            if (canWinInControl(remaining, depth - 1)) return true
        }
        return false
    }

    // ==================== 手牌规划 ====================

    /** 估算手牌最少拆分手数（越少说明牌越紧凑） */
    private fun estimateHandTurns(hand: List<Card>): Int {
        if (hand.isEmpty()) return 0
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }.toMutableMap()
        var turns = 0

        if ((counts[16] ?: 0) > 0 && (counts[17] ?: 0) > 0) {
            turns++
            counts.remove(16)
            counts.remove(17)
        }
        val bombs = counts.filterValues { it == 4 }.keys.sorted()
        bombs.forEach { counts.remove(it) }
        turns += bombs.size

        turns += consumeStructureRuns(counts, 3, 2)
        turns += consumeStructureRuns(counts, 2, 3)
        turns += consumeStructureRuns(counts, 1, 5)

        val triples = counts.filterValues { it == 3 }.keys.sorted()
        for (r in triples) {
            if ((counts[r] ?: 0) != 3) continue
            val pairKicker = counts.entries.firstOrNull { it.key != r && it.value >= 2 && !isControlRank(it.key) }
                ?: counts.entries.firstOrNull { it.key != r && it.value >= 2 }
            if (pairKicker != null) {
                counts[pairKicker.key] = pairKicker.value - 2
                if (counts[pairKicker.key] == 0) counts.remove(pairKicker.key)
            } else {
                val singleKicker = counts.entries.firstOrNull { it.key != r && it.value >= 1 && !isControlRank(it.key) }
                    ?: counts.entries.firstOrNull { it.key != r && it.value >= 1 }
                if (singleKicker != null) {
                    counts[singleKicker.key] = singleKicker.value - 1
                    if (counts[singleKicker.key] == 0) counts.remove(singleKicker.key)
                }
            }
            counts.remove(r)
            turns++
        }

        for ((_, n) in counts.entries.sortedBy { it.key }) {
            turns += n / 2
            if (n % 2 == 1) turns += 1
        }
        return turns
    }

    private fun consumeStructureRuns(counts: MutableMap<Int, Int>, m: Int, minLen: Int): Int {
        var plays = 0
        val ranks = counts.filterValues { it >= m }.keys.filter { it in 3..14 }.sorted()
        var i = 0
        while (i < ranks.size) {
            var j = i
            while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
            if (j - i + 1 >= minLen) {
                plays++
                for (k in i..j) {
                    counts[ranks[k]] = counts[ranks[k]]!! - m
                    if (counts[ranks[k]] == 0) counts.remove(ranks[k])
                }
            }
            i = j + 1
        }
        return plays
    }

    /** 整手分解为出牌组，供领出选择参考 */
    private fun buildPlan(hand: List<Card>): List<CardGroup> {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }.toMutableMap()
        val cardsByRank = hand.groupByTo(mutableMapOf()) { it.rank }
        val groups = mutableListOf<CardGroup>()

        fun takeCards(r: Int, n: Int): List<Card> {
            val pool = cardsByRank.getValue(r)
            val taken = pool.take(n)
            cardsByRank[r] = pool.drop(n).toMutableList()
            return taken
        }

        // 武器（炸弹/火箭）放最后
        val weapons = mutableListOf<CardGroup>()
        if ((counts[16] ?: 0) > 0 && (counts[17] ?: 0) > 0) {
            weapons.add(CardGroup(CardType.ROCKET, 17, 1, takeCards(16, 1) + takeCards(17, 1)))
            counts.remove(16)
            counts.remove(17)
        }
        for (r in counts.filterValues { it == 4 }.keys.sorted()) {
            weapons.add(CardGroup(CardType.BOMB, r, 1, takeCards(r, 4)))
            counts.remove(r)
        }

        // 结构牌
        groups.addAll(extractRuns(counts, cardsByRank, 3, 2, CardType.PLANE))
        groups.addAll(extractRuns(counts, cardsByRank, 2, 3, CardType.STRAIGHT_PAIR))
        groups.addAll(extractRuns(counts, cardsByRank, 1, 5, CardType.STRAIGHT))

        // 三带一/三带二/三张
        for (r in counts.filterValues { it == 3 }.keys.sorted()) {
            if ((counts[r] ?: 0) < 3) continue
            val three = takeCards(r, 3)
            val pairKick = kickerFor(counts, cardsByRank, r, 2, true) ?: kickerFor(counts, cardsByRank, r, 2, false)
            if (pairKick != null) {
                groups.add(CardGroup(CardType.TRIPLE_TWO, r, 1, three + pairKick.cards))
                counts[pairKick.rank] = counts[pairKick.rank]!! - 2
                if (counts[pairKick.rank] == 0) counts.remove(pairKick.rank)
            } else {
                val singleKick = kickerFor(counts, cardsByRank, r, 1, true) ?: kickerFor(counts, cardsByRank, r, 1, false)
                if (singleKick != null) {
                    groups.add(CardGroup(CardType.TRIPLE_ONE, r, 1, three + singleKick.cards))
                    counts[singleKick.rank] = counts[singleKick.rank]!! - 1
                    if (counts[singleKick.rank] == 0) counts.remove(singleKick.rank)
                } else {
                    groups.add(CardGroup(CardType.TRIPLE, r, 1, three))
                }
            }
            counts.remove(r)
        }

        // 对子与单张
        for ((r, n) in counts.entries.sortedBy { it.key }.toList()) {
            if (n >= 2) {
                groups.add(CardGroup(CardType.PAIR, r, 1, takeCards(r, 2)))
                counts[r] = n - 2
            }
        }
        for ((r, n) in counts.entries.sortedBy { it.key }.toList()) {
            if (n >= 1) groups.add(CardGroup(CardType.SINGLE, r, 1, takeCards(r, 1)))
        }

        groups.addAll(weapons)
        return groups
    }

    private data class Kicker(val rank: Int, val cards: List<Card>)

    private fun kickerFor(
        counts: MutableMap<Int, Int>,
        cardsByRank: MutableMap<Int, MutableList<Card>>,
        excludeRank: Int,
        need: Int,
        excludeControl: Boolean
    ): Kicker? {
        val entry = counts.entries.firstOrNull {
            it.key != excludeRank && it.value >= need && (!excludeControl || !isControlRank(it.key))
        } ?: return null
        val pool = cardsByRank.getValue(entry.key)
        val taken = pool.take(need)
        cardsByRank[entry.key] = pool.drop(need).toMutableList()
        return Kicker(entry.key, taken)
    }

    private fun extractRuns(
        counts: MutableMap<Int, Int>,
        cardsByRank: MutableMap<Int, MutableList<Card>>,
        m: Int,
        minLen: Int,
        type: CardType
    ): List<CardGroup> {
        val result = mutableListOf<CardGroup>()
        val ranks = counts.filterValues { it >= m }.keys.filter { it in 3..14 }.sorted()
        var i = 0
        while (i < ranks.size) {
            var j = i
            while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
            if (j - i + 1 >= minLen) {
                val runCards = mutableListOf<Card>()
                for (k in i..j) {
                    val rk = ranks[k]
                    runCards.addAll(cardsByRank.getValue(rk).take(m))
                    cardsByRank[rk] = cardsByRank.getValue(rk).drop(m).toMutableList()
                }
                result.add(CardGroup(type, ranks[i], j - i + 1, runCards))
                for (k in i..j) {
                    counts[ranks[k]] = counts[ranks[k]]!! - m
                    if (counts[ranks[k]] == 0) counts.remove(ranks[k])
                }
            }
            i = j + 1
        }
        return result
    }

    // ==================== 威胁评估 ====================

    /** 农民视角威胁折减：unseen 同时含队友与对手牌，按对手占比折减 */
    private fun scaledThreat(count: Int): Int {
        if (ctxRole != PlayerRole.FARMER) return count
        val total = ctxMinOpponentCards + ctxTeammateCardCount
        if (total <= 0) return 0
        if (count <= 0) return 0
        return Math.round(count * ctxMinOpponentCards.toFloat() / total)
    }

    /** 出这手的被压威胁（单张/对子统计更高 unseen 同型；结构牌看更高 run） */
    private fun feedDanger(group: CardGroup): Int {
        val rawDanger = when (group.type) {
            CardType.SINGLE -> {
                var d = 0
                for (r in (group.mainRank + 1)..17) if (r < ctxUnseenCounts.size) d += ctxUnseenCounts[r]
                d
            }
            CardType.PAIR -> {
                var d = 0
                for (r in (group.mainRank + 1)..15) if (r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 2) d++
                d
            }
            CardType.STRAIGHT -> if (maxUnseenRun(group.mainRank, 1) >= group.length) 4 else 0
            CardType.STRAIGHT_PAIR -> if (maxUnseenRun(group.mainRank, 2) >= group.length) 4 else 0
            CardType.PLANE, CardType.PLANE_SINGLE, CardType.PLANE_PAIR ->
                if (maxUnseenRun(group.mainRank, 3) >= group.length) 4 else 0
            CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO ->
                if ((group.mainRank + 1..15).any { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 3 }) 4 else 0
            else -> 1
        }
        var danger = scaledThreat(rawDanger)
        if (ctxMinOpponentCards == 1 && group.type == CardType.SINGLE && danger > 0) danger += 50
        return danger
    }

    /** 绝对不可被压：依据 unseen 推导当前通吃 */
    private fun isUnbeatable(group: CardGroup): Boolean {
        return when (group.type) {
            CardType.SINGLE -> (group.mainRank + 1..17).none { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] > 0 }
            CardType.PAIR -> (group.mainRank + 1..15).none { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 2 }
            CardType.STRAIGHT -> maxUnseenRun(group.mainRank, 1) < group.length
            CardType.STRAIGHT_PAIR -> maxUnseenRun(group.mainRank, 2) < group.length
            CardType.PLANE, CardType.PLANE_SINGLE, CardType.PLANE_PAIR ->
                maxUnseenRun(group.mainRank, 3) < group.length
            CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO ->
                (group.mainRank + 1..15).none { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 3 }
            else -> false
        }
    }

    private fun maxUnseenRun(minRank: Int, needPerRank: Int): Int {
        var best = 0
        var cur = 0
        for (r in 3..14) {
            if (r > minRank && r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= needPerRank) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best
    }

    private fun hasUnseenHigherBombOrRocket(myBombRank: Int): Boolean {
        if (ctxUnseenCounts.size < 18) return false
        if (ctxUnseenCounts[16] == 1 && ctxUnseenCounts[17] == 1 && ctxMaxOpponentCards >= 2) return true
        for (r in (myBombRank + 1)..15) {
            if (ctxUnseenCounts[r] == 4) return true
        }
        return false
    }

    private fun hasUnseenBombOrRocket(): Boolean {
        if (ctxUnseenCounts.size < 18) return false
        if (ctxUnseenCounts[16] == 1 && ctxUnseenCounts[17] == 1 && ctxMaxOpponentCards >= 2) return true
        for (r in 3..15) if (ctxUnseenCounts[r] == 4) return true
        return false
    }
}
