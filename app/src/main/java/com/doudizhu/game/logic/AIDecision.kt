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
     * @param landlordIndex 地主玩家索引（用于判断队友）
     * @param unseenCounts 各rank在对手手中的剩余张数（长度18，下标即rank，用于牌池判断）
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
        unseenCounts: IntArray = intArrayOf()
    ): CardGroup? {
        // 本轮首家：上一手为空，或上一手就是自己出的牌（即轮转回自己，应重新自由出牌）
        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            (myIndex >= 0 && lastPlayerIndex == myIndex)

        // 本轮首家必须出牌（自由续出），绝不压自己的牌，也绝不跳过
        return when (difficulty) {
            Difficulty.EASY -> if (isFreeLead) easyFreePlay(hand) else easyDecision(hand, lastPlay)
            Difficulty.NORMAL -> if (isFreeLead) {
                freePlayStrategy(
                    hand, CardRuleEngine.findAllValidPlays(hand, null), role, teammateCardCount,
                    opponentCardCounts, unseenCounts
                )
            } else {
                normalDecision(hand, lastPlay, role, teammateCardCount, lastPlayerIndex, myIndex,
                    opponentCardCounts, landlordIndex, unseenCounts)
            }
        }
    }

    /**
     * 简单难度：自由出牌先出最小的单张，其次对子，保持简单但合理
     */
    private fun easyFreePlay(hand: List<Card>): CardGroup? {
        val plays = CardRuleEngine.findAllValidPlays(hand, null)
        val singles = plays.filter { it.type == CardType.SINGLE }
        if (singles.isNotEmpty()) return singles.minByOrNull { it.mainRank }
        val pairs = plays.filter { it.type == CardType.PAIR }
        if (pairs.isNotEmpty()) return pairs.minByOrNull { it.mainRank }
        return plays.randomOrNull()
    }

    /**
     * AI智能叫分决策
     * 根据手牌强度、AI难度和当前最高叫分决定叫分
     * 简单AI更保守，普通AI更激进
     * @param hand 初始手牌（17张）
     * @param currentMaxBid 当前最高叫分
     * @param difficulty AI难度
     * @return 叫分（0-3），0表示不叫
     */
    fun decideBid(hand: List<Card>, currentMaxBid: Int, difficulty: Difficulty): Int {
        // 手牌强度 + 底牌期望加成（地主可额外拿3张，缺一张即可能凑炸弹/飞机） + 小额抖动保多样
        val strength = evaluateHandStrength(hand) + bottomCardBonus(hand) + (0..2).random()
        // 竞叫升级惩罚：当前已被叫到2分时，反抢3分需更高门槛（风险收益）
        val escalation = if (currentMaxBid >= 2) 6 else 0

        return when (difficulty) {
            // 简单AI：非常保守，只有极强才叫，几乎不抢3分
            Difficulty.EASY -> when {
                strength >= 85 + escalation -> 3
                strength >= 70 + escalation && currentMaxBid < 2 -> 2
                strength >= 58 + escalation && currentMaxBid < 1 -> 1
                else -> 0
            }
            // 普通AI：适度积极，牌好就抢
            Difficulty.NORMAL -> when {
                strength >= 72 + escalation -> 3
                strength >= 58 + escalation && currentMaxBid < 2 -> 2
                strength >= 46 + escalation && currentMaxBid < 1 -> 1
                else -> 0
            }
        }
    }

    /**
     * 评估手牌强度（0-100分）
     * 考虑因素：大牌数量、炸弹/火箭、牌型连贯性
     */
    private fun evaluateHandStrength(hand: List<Card>): Int {
        var score = 0

        // 1. 统计大牌（A、2、小王、大王）
        val aces = hand.count { it.rank == 14 }
        val twos = hand.count { it.rank == 15 }  // 2
        val smallJoker = hand.count { it.rank == 16 }
        val bigJoker = hand.count { it.rank == 17 }

        // 大王 +25分，小王 +20分
        score += bigJoker * 25
        score += smallJoker * 20

        // 2的数量：每张 +8分；A +4分
        score += twos * 8
        score += aces * 4

        // 2. 统计炸弹
        val rankCounts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val bombs = rankCounts.count { it.value == 4 }
        score += bombs * 22  // 每个炸弹 +22分

        // 火箭（双王）
        if (smallJoker > 0 && bigJoker > 0) {
            score += 15  // 额外加分
        }

        // 3. 牌型连贯性（顺子/连对/飞机潜力）
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

        // 连对潜力：连续 3+ 个 rank 各至少 2 张
        val pairRanks = rankCounts.filterValues { it >= 2 }.keys.filter { it in 3..14 }.sorted()
        var i = 0
        while (i < pairRanks.size) {
            var j = i
            while (j + 1 < pairRanks.size && pairRanks[j + 1] == pairRanks[j] + 1) j++
            val runLen = j - i + 1
            if (runLen >= 3) score += 6 + (runLen - 3) * 3
            i = j + 1
        }

        // 飞机潜力：连续 2+ 个 rank 各至少 3 张
        val tripleRanks = rankCounts.filterValues { it >= 3 }.keys.filter { it in 3..14 }.sorted()
        i = 0
        while (i < tripleRanks.size) {
            var j = i
            while (j + 1 < tripleRanks.size && tripleRanks[j + 1] == tripleRanks[j] + 1) j++
            if (j - i + 1 >= 2) score += 10
            i = j + 1
        }

        // 4. 三张数量（三带潜力）
        val triples = rankCounts.count { it.value == 3 }
        score += triples * 6

        // 5. 对子数量
        val pairs = rankCounts.count { it.value == 2 }
        score += pairs * 2

        // 6. 紧凑度奖励：手数越少说明牌越成结构、越强
        val turns = estimateHandTurns(hand)
        score += (20 - turns * 2).coerceAtLeast(0)

        return score.coerceIn(0, 100)
    }

    /**
     * 底牌期望加成：地主可额外拿 3 张底牌，
     * 已持有 3 张的 rank 更可能被底牌补成炸弹/飞机/成整结构
     */
    private fun bottomCardBonus(hand: List<Card>): Int {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val tripCount = counts.count { it.value == 3 }
        return 3 + tripCount * 2
    }

    /**
     * 估算手牌最少拆分手数（手数越少说明牌越紧凑、越强）
     * 贪心优先消耗火箭/炸弹/飞机/连对/顺子，再拆三带/对子/单张
     */
    private fun estimateHandTurns(hand: List<Card>): Int {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }.toMutableMap()
        var turns = 0

        // 1. 火箭（双王）算一手
        if ((counts[16] ?: 0) > 0 && (counts[17] ?: 0) > 0) {
            turns++
            counts.remove(16)
            counts.remove(17)
        }

        // 2. 炸弹每副一手
        val bombs = counts.filterValues { it == 4 }.keys.sorted()
        bombs.forEach { counts.remove(it) }
        turns += bombs.size

        // 3. 结构牌：飞机（连续且各至少3张）→ 连对（各至少2张）→ 顺子（各至少1张）
        turns += consumeStructureRuns(counts, 3, 2)
        turns += consumeStructureRuns(counts, 2, 3)
        turns += consumeStructureRuns(counts, 1, 5)

        // 4. 三带一/三带二：三张优先带一对，其次带一张，尽量一把出完
        val tripleSet = counts.filterValues { it == 3 }.keys.toMutableSet()
        for (r in tripleSet.toList()) {
            if ((counts[r] ?: 0) != 3) continue
            // 优先从非三张 rank 取对子作带牌，避免拆散其他三张
            val pairKicker = counts.entries.firstOrNull { it.key != r && it.key !in tripleSet && it.value >= 2 }
                ?: counts.entries.firstOrNull { it.key != r && it.value >= 2 }
            if (pairKicker != null) {
                counts[pairKicker.key] = pairKicker.value - 2
                if (counts[pairKicker.key] == 0) counts.remove(pairKicker.key)
            } else {
                val singleKicker = counts.entries.firstOrNull { it.key != r && it.key !in tripleSet && it.value >= 1 }
                    ?: counts.entries.firstOrNull { it.key != r && it.value >= 1 }
                if (singleKicker != null) {
                    counts[singleKicker.key] = singleKicker.value - 1
                    if (counts[singleKicker.key] == 0) counts.remove(singleKicker.key)
                }
            }
            counts.remove(r)
            tripleSet.remove(r)
            turns++
        }

        // 5. 对子与剩余单张
        for ((_, n) in counts.entries.sortedBy { it.key }) {
            turns += n / 2
            if (n % 2 == 1) turns += 1
        }

        return turns
    }

    /**
     * 消耗一段连续 rank 结构（各至少 m 张）为单手牌，统计能组合出几手
     * 每段连续 run 只要长度达标，即可整段打成一手
     */
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

    /**
     * 简单难度：随机出牌，能管就管（比纯随机多一份克制：不随意拆结构、少用炸弹）
     */
    private fun easyDecision(hand: List<Card>, lastPlay: CardGroup?): CardGroup? {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) return null

        // 自由出牌走 easyFreePlay
        if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            return easyFreePlay(hand)
        }

        // 需要管牌时：70%概率用最小的普通牌管，40%概率用炸弹
        val nonBombPlays = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        if (nonBombPlays.isNotEmpty() && Math.random() < 0.7) {
            return nonBombPlays.minByOrNull { it.mainRank }
        }
        val bombs = validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }
        if (bombs.isNotEmpty() && Math.random() < 0.4) {
            return bombs.minByOrNull { it.mainRank }
        }
        return null
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
        opponentCardCounts: IntArray,
        landlordIndex: Int,
        unseenCounts: IntArray
    ): CardGroup? {
        val validPlays = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (validPlays.isEmpty()) return null

        // 自由出牌策略
        if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            return freePlayStrategy(hand, validPlays, role, teammateCardCount, opponentCardCounts, unseenCounts)
        }

        // 跟牌策略（考虑竞合关系）
        return followPlayStrategy(hand, validPlays, lastPlay, role, lastPlayerIndex, myIndex, opponentCardCounts, landlordIndex, unseenCounts)
    }

    /**
     * 自由出牌策略（增强版）
     * 考虑身份、队友状态、对手状态、牌池信息
     */
    private fun freePlayStrategy(
        hand: List<Card>,
        validPlays: List<CardGroup>,
        role: PlayerRole,
        teammateCardCount: Int,
        opponentCardCounts: IntArray,
        unseenCounts: IntArray
    ): CardGroup {
        // 如果手牌只剩一手，直接出完
        val fullHand = CardRuleEngine.identify(hand)
        if (fullHand.type != CardType.INVALID) {
            return fullHand
        }

        // A. 整手分解规划（提前计算，供多个分支使用）
        val plan = buildHandPlan(hand)

        // 农民配合 + B. 报单喂队友
        if (role == PlayerRole.FARMER && teammateCardCount in 1..2) {
            if (teammateCardCount == 1) {
                // 队友报单：领出最小非控单张，让队友好接走完
                val feed = plan.firstOrNull { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
                    ?: plan.firstOrNull { it.type == CardType.SINGLE }
                    ?: plan.firstOrNull { it.type == CardType.PAIR }
                if (feed != null) return feed
            } else {
                // 队友剩2张：出大牌帮顶
                val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
                if (bigPlays.isNotEmpty()) {
                    return bigPlays.maxByOrNull { it.mainRank }!!
                }
            }
        }

        // 地主策略：手牌少时积极出大牌
        if (role == PlayerRole.LANDLORD && hand.size <= 5) {
            val bigPlays = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
            if (bigPlays.isNotEmpty()) {
                return bigPlays.maxByOrNull { it.mainRank }!!
            }
        }

        // 对手牌少时，出大牌压制（F：地主用2/王回手，此处保留大牌抢回控制）
        val minOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min() else 20

        // A. 残局必胜（1 层递归）：若能必然冲完，直接连出，不做其它权衡
        val guaranteed = canFinishGuaranteed(hand, validPlays, minOpponentCards, unseenCounts)
        if (guaranteed != null) return guaranteed

        if (minOpponentCards <= 3) {
            // 若2/王等控牌全部已出（对手手里没有），则小牌也能控场，优先出最小的
            val controlUnseen = (15..17).sumOf { r -> if (r < unseenCounts.size) unseenCounts[r] else 0 }
            val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
            if (bigPlays.isNotEmpty()) {
                return if (controlUnseen == 0) bigPlays.minByOrNull { it.mainRank }!!
                else bigPlays.maxByOrNull { it.mainRank }!!
            }
        }

        // A. 残局前瞻（增强）：手牌较少时枚举领出，防喂杀 + 最小剩余手数 + 优先不可被反制的牌
        if (hand.size <= 8) {
            data class Cand(val group: CardGroup, val turns: Int, val danger: Int, val unbeatable: Boolean)
            val cands = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
                .map { c ->
                    val remaining = hand.filter { card -> c.cards.none { it.id == card.id } }
                    Cand(
                        c,
                        estimateHandTurns(remaining),
                        feedDanger(c, unseenCounts, minOpponentCards),
                        isUnbeatable(c, unseenCounts)
                    )
                }
            if (cands.isNotEmpty()) {
                val best = cands.minWithOrNull(
                    compareBy({ it.danger }, { if (it.unbeatable) 0 else 1 }, { it.turns }, { it.group.mainRank })
                )
                if (best != null) return best.group
            }
        }

        // D. 地主钓牌：手牌较优（较大且紧凑）且无必杀压力时，偶尔出中位诱饵勾出对手的2/王
        if (role == PlayerRole.LANDLORD && hand.size > 8 && plan.size <= 6 && Math.random() < 0.30) {
            val bait = pickBait(plan)
            if (bait != null) return bait
        }

        // 优先领出「非控牌」的结构组：顺子/连对/飞机/三带（一把甩掉多张垃圾）
        for (group in plan) {
            if (isControlRank(group.mainRank)) continue
            if (group.type == CardType.STRAIGHT || group.type == CardType.STRAIGHT_PAIR ||
                group.type == CardType.PLANE || group.type == CardType.TRIPLE ||
                group.type == CardType.TRIPLE_ONE || group.type == CardType.TRIPLE_TWO ||
                group.type == CardType.PAIR) {
                return group
            }
        }
        // 2. 领出安全度：非控单张/对子中选「对手最难压住」（threat 最小）的，同威胁出最小
        val singlePair = plan.filter {
            (it.type == CardType.SINGLE || it.type == CardType.PAIR) && !isControlRank(it.mainRank)
        }
        if (singlePair.isNotEmpty()) {
            return singlePair.minWithOrNull(
                compareBy({ leadThreat(it, unseenCounts) }, { it.mainRank })
            )!!
        }
        // 兜底：最小可出的普通牌
        val nonBomb = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        return nonBomb.minByOrNull { it.mainRank } ?: validPlays.first()
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
        lastPlayerIndex: Int,
        myIndex: Int,
        opponentCardCounts: IntArray,
        landlordIndex: Int,
        unseenCounts: IntArray
    ): CardGroup? {
        // 如果手牌数量 <= 出牌数量，考虑直接出完
        val fullHand = CardRuleEngine.identify(hand)
        val canFinishNow = fullHand.type != CardType.INVALID && CardRuleEngine.isValidPlay(fullHand, lastPlay)
        if (canFinishNow) {
            return fullHand
        }

        // 判断上一手是否是队友出的
        val isTeammatePlayed = isTeammate(lastPlayerIndex, myIndex, role, landlordIndex)

        // 农民绝不压队友（除非自己能一手出完）；地主没有队友
        if (role == PlayerRole.FARMER && isTeammatePlayed) {
            return null
        }

        // 非炸弹/火箭的出牌选择
        val normalPlays = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        val minOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min() else 20

        // D. 位置修正：地主刚出完，轮到我时队友（介于地主与我之间）已过且快出完 → 放过，让队友好走
        if (role == PlayerRole.FARMER && landlordIndex == lastPlayerIndex) {
            val between = (lastPlayerIndex + 1) % 3
            if (between != myIndex && between != landlordIndex && teammateCardCount in 1..2) {
                return null
            }
            // 深度合作：否则若我有便宜非控牌可接过，主动接过保护队友好走
            if (minOpponentCards > 3) {
                val cheap = normalPlays.filter { it.type == lastPlay.type && !isControlRank(it.mainRank) }
                if (cheap.isNotEmpty()) {
                    return chooseFollowPlay(cheap, lastPlay, hand)
                }
            }
        }

        if (normalPlays.isNotEmpty()) {
            // 对手快出完时，无条件出最大压制
            if (minOpponentCards <= 3) {
                return normalPlays.maxByOrNull { it.mainRank }
            }
            // C. 农民跟牌安全：若跟牌必被更高同型压回且需动用控牌，非紧急时保留控牌
            val higher = if (lastPlay.type == CardType.SINGLE || lastPlay.type == CardType.PAIR)
                higherOppRanks(lastPlay.type, lastPlay.mainRank, unseenCounts) else 0
            if (role == PlayerRole.FARMER && higher > 2) {
                val nonControl = normalPlays.filter { it.type == lastPlay.type && !isControlRank(it.mainRank) }
                if (nonControl.isNotEmpty()) {
                    return chooseFollowPlay(nonControl, lastPlay, hand)
                }
                return null
            }
            // 5. 主动过牌（地主）：只有动用2/王等控牌才压得住、且对手出的是小牌、非紧急 → 保存控牌
            if (role == PlayerRole.LANDLORD && lastPlay.mainRank <= 9) {
                val candidates = normalPlays.filter { it.type == lastPlay.type }
                if (candidates.isNotEmpty() && candidates.all { isControlRank(it.mainRank) }) {
                    return null
                }
            }
            // B. 正常情况：成本aware选择（破坏结构最小、其次最小、控牌最后）
            return chooseFollowPlay(normalPlays, lastPlay, hand)
        }

        // E. 炸弹分层时机
        val bombs = validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }
        if (bombs.isNotEmpty() && !isTeammatePlayed) {
            return decideBomb(bombs, hand, role, teammateCardCount, minOpponentCards, unseenCounts)
        }

        return null  // 不出
    }

    /**
     * 成本-aware 跟牌选择（B）：
     * 同型能压的候选里，优先「独立成组、不破坏剩余结构」，其次出最小，最后才动控牌
     */
    private fun chooseFollowPlay(
        normalPlays: List<CardGroup>,
        lastPlay: CardGroup,
        hand: List<Card>
    ): CardGroup? {
        val candidates = normalPlays.filter { it.type == lastPlay.type }
        if (candidates.isEmpty()) return normalPlays.minByOrNull { it.mainRank }
        // #3 残局拆牌换算：手牌少时，不再只看"是否破坏结构"，
        // 而是选「拆散后剩余手数最少」的跟法（绝不留难走的散张/双三）
        if (hand.size <= 6) {
            return candidates.minWithOrNull(
                compareBy(
                    { estimateHandTurns(hand.filter { card -> it.cards.none { c -> c.id == card.id } }) },
                    { it.mainRank }
                )
            ) ?: candidates.minByOrNull { it.mainRank }
        }
        val plan = buildHandPlan(hand)
        return candidates.minWithOrNull(compareBy({ followCost(it, plan) }, { it.mainRank }))
            ?: normalPlays.minByOrNull { it.mainRank }
    }

    /**
     * 跟牌成本：独立成组(0) / 拆结构(2) / 动用控牌(+4)，越低越优
     */
    private fun followCost(candidate: CardGroup, plan: List<CardGroup>): Int {
        val intact = plan.any {
            it.type == candidate.type && it.size == candidate.size && it.mainRank == candidate.mainRank
        }
        var cost = if (intact) 0 else 2
        if (isControlRank(candidate.mainRank)) cost += 4
        return cost
    }

    /**
     * 判断上一手出牌的玩家是否是队友
     * 地主：没有队友
     * 农民：另一个农民是队友（即不是我、也不是地主的那个玩家）
     */
    private fun isTeammate(lastPlayerIndex: Int, myIndex: Int, role: PlayerRole, landlordIndex: Int): Boolean {
        if (role == PlayerRole.LANDLORD) return false
        if (lastPlayerIndex < 0 || myIndex < 0) return false
        if (lastPlayerIndex == myIndex) return false
        // 如果上家既不是我，也不是地主，则必为另一个农民（队友）
        return lastPlayerIndex != landlordIndex
    }

    // ==================== 优化增强（A-F） ====================

    /** 控牌：2(15)/小王(16)/大王(17) 为高位控制牌，应尽量留后回收 */
    private val ControlRanks = setOf(15, 16, 17)

    private fun isControlRank(rank: Int): Boolean = rank in ControlRanks

    /**
     * A. 整手分解规划：把 17 张拆成近乎最少出牌组，输出「先出垃圾、后留武器」的有序计划
     */
    private fun buildHandPlan(hand: List<Card>): List<CardGroup> {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }.toMutableMap()
        val cardsByRank = hand.groupBy { it.rank }
        val plan = mutableListOf<CardGroup>()
        val weapons = mutableListOf<CardGroup>()  // 炸弹/火箭收尾

        // 火箭
        if ((counts[16] ?: 0) > 0 && (counts[17] ?: 0) > 0) {
            weapons.add(CardGroup(CardType.ROCKET, 17, 1, cardsByRank.getValue(16) + cardsByRank.getValue(17)))
            counts.remove(16)
            counts.remove(17)
        }
        // 炸弹
        counts.filterValues { it == 4 }.keys.sorted().forEach { r ->
            weapons.add(CardGroup(CardType.BOMB, r, 1, cardsByRank.getValue(r)))
            counts.remove(r)
        }

        // 结构牌：飞机 → 连对 → 顺子（先甩）
        plan.addAll(extractRuns(counts, cardsByRank, 3, 2, CardType.PLANE))
        plan.addAll(extractRuns(counts, cardsByRank, 2, 3, CardType.STRAIGHT_PAIR))
        plan.addAll(extractRuns(counts, cardsByRank, 1, 5, CardType.STRAIGHT))

        // 三带一/三带二/三张：优先用非控牌作带牌
        val triples = counts.filterValues { it == 3 }.keys.sorted()
        for (r in triples) {
            if ((counts[r] ?: 0) < 3) continue
            val three = cardsByRank.getValue(r).take(3)
            val pairKick = kickerFor(counts, r, 2, cardsByRank, excludeControl = true)
                ?: kickerFor(counts, r, 2, cardsByRank, excludeControl = false)
            if (pairKick != null) {
                plan.add(CardGroup(CardType.TRIPLE_TWO, r, 1, three + pairKick.cards))
                counts[pairKick.rank] = counts[pairKick.rank]!! - 2
                if (counts[pairKick.rank] == 0) counts.remove(pairKick.rank)
            } else {
                val singleKick = kickerFor(counts, r, 1, cardsByRank, excludeControl = true)
                    ?: kickerFor(counts, r, 1, cardsByRank, excludeControl = false)
                if (singleKick != null) {
                    plan.add(CardGroup(CardType.TRIPLE_ONE, r, 1, three + singleKick.cards))
                    counts[singleKick.rank] = counts[singleKick.rank]!! - 1
                    if (counts[singleKick.rank] == 0) counts.remove(singleKick.rank)
                } else {
                    plan.add(CardGroup(CardType.TRIPLE, r, 1, three))
                }
            }
            counts.remove(r)
        }

        // 对子
        val pairs = mutableListOf<CardGroup>()
        for ((r, n) in counts.entries.sortedBy { it.key }.toList()) {
            if (n >= 2) {
                pairs.add(CardGroup(CardType.PAIR, r, 1, cardsByRank.getValue(r).take(2)))
                counts[r] = n - 2
            }
        }
        // 散单
        val singles = mutableListOf<CardGroup>()
        for ((r, n) in counts.entries.sortedBy { it.key }.toList()) {
            if (n >= 1) singles.add(CardGroup(CardType.SINGLE, r, 1, cardsByRank.getValue(r).take(1)))
        }

        // 组装顺序：非控对子/单张优先，控牌靠后，武器收尾（F 控牌纪律）
        plan.addAll(pairs.filter { !isControlRank(it.mainRank) })
        plan.addAll(singles.filter { !isControlRank(it.mainRank) })
        plan.addAll(pairs.filter { isControlRank(it.mainRank) })
        plan.addAll(singles.filter { isControlRank(it.mainRank) })
        plan.addAll(weapons)
        return plan
    }

    private data class Kicker(val rank: Int, val cards: List<Card>)

    /** 找一个可作带牌的 rank（张数足够，可排除控牌/排除主 rank） */
    private fun kickerFor(
        counts: MutableMap<Int, Int>,
        excludeRank: Int,
        need: Int,
        cardsByRank: Map<Int, List<Card>>,
        excludeControl: Boolean
    ): Kicker? {
        val entry = counts.entries.firstOrNull {
            it.key != excludeRank && it.value >= need && (!excludeControl || !isControlRank(it.key))
        } ?: return null
        return Kicker(entry.key, cardsByRank.getValue(entry.key).take(need))
    }

    /**
     * 抽取一段连续 rank 结构（各至少 m 张）为一手牌，并扣减计数
     */
    private fun extractRuns(
        counts: MutableMap<Int, Int>,
        cardsByRank: Map<Int, List<Card>>,
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
                result.add(CardGroup(type, ranks[i], j - i + 1, (i..j).flatMap { cardsByRank.getValue(ranks[it]).take(m) }))
                for (k in i..j) {
                    counts[ranks[k]] = counts[ranks[k]]!! - m
                    if (counts[ranks[k]] == 0) counts.remove(ranks[k])
                }
            }
            i = j + 1
        }
        return result
    }

    /** C. 统计对手未见的、能压住该牌型的更高 rank 数量 */
    private fun higherOppRanks(groupType: CardType, mainRank: Int, unseenCounts: IntArray): Int {
        var count = 0
        when (groupType) {
            CardType.SINGLE -> for (r in (mainRank + 1)..17) if (r < unseenCounts.size && unseenCounts[r] > 0) count++
            CardType.PAIR -> for (r in (mainRank + 1)..15) if (r < unseenCounts.size && unseenCounts[r] >= 2) count++
            else -> {}
        }
        return count
    }

    /** E. 炸弹分层：防杀/收尾/救队友/安全翻倍才炸，不盲目概率炸 */
    private fun decideBomb(
        bombs: List<CardGroup>,
        hand: List<Card>,
        role: PlayerRole,
        teammateCardCount: Int,
        minOpponentCards: Int,
        unseenCounts: IntArray
    ): CardGroup? {
        val b = bombs.minByOrNull { it.mainRank } ?: return null
        // 1. 对手距获胜很近，必须炸阻止
        if (minOpponentCards <= 3) return b
        // 2. 出炸后剩余手牌若成一整手，炸完可收尾
        val remaining = hand.filter { c -> b.cards.none { it.id == c.id } }
        if (remaining.isNotEmpty() && remaining.size <= 5 && CardRuleEngine.identify(remaining).type != CardType.INVALID) {
            return b
        }
        // 3. 农民炸救队友：队友快出完时抢回控制权护送
        if (role == PlayerRole.FARMER && teammateCardCount in 1..2) return b
        // 4. 安全翻倍炸：对手手中已无任何2/王，炸弹不可被反制且能翻倍
        val controls = (15..17).sumOf { r -> if (r < unseenCounts.size) unseenCounts[r] else 0 }
        if (controls == 0) return b
        // 其余情况保留炸弹，避免交回控制权
        return null
    }

    /** 2. 领出威胁度：对手能压住该单张/对子的更高同型 rank 数量，越小越安全 */
    private fun leadThreat(group: CardGroup, unseenCounts: IntArray): Int {
        return when (group.type) {
            CardType.SINGLE -> (group.mainRank + 1..17).count { r -> r < unseenCounts.size && unseenCounts[r] > 0 }
            CardType.PAIR -> (group.mainRank + 1..15).count { r -> r < unseenCounts.size && unseenCounts[r] >= 2 }
            else -> 100
        }
    }

    /** D. 地主钓牌：从计划里挑一手中位对子/单张作诱饵，勾出对手的2/王 */
    private fun pickBait(plan: List<CardGroup>): CardGroup? {
        val pairs = plan.filter { it.type == CardType.PAIR && !isControlRank(it.mainRank) }
        val singles = plan.filter { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
        if (Math.random() < 0.5 && pairs.isNotEmpty()) {
            return pairs.firstOrNull { it.mainRank >= 9 } ?: pairs.minByOrNull { it.mainRank }
        }
        if (singles.isNotEmpty()) {
            return singles.firstOrNull { it.mainRank >= 9 } ?: singles.minByOrNull { it.mainRank }
        }
        return null
    }

    /**
     * B. 领出威胁度（残局增强版）：单/对统计更高不可见同型数量；
     * 若对手报单（剩 1 张）且该单张能被接，则喂杀风险极大 → 大幅抬高威胁
     */
    private fun feedDanger(group: CardGroup, unseenCounts: IntArray, minOpponentCards: Int): Int {
        var danger = when (group.type) {
            CardType.SINGLE -> {
                var d = 0
                for (r in (group.mainRank + 1)..17) if (r < unseenCounts.size && unseenCounts[r] > 0) d++
                d
            }
            CardType.PAIR -> {
                var d = 0
                for (r in (group.mainRank + 1)..15) if (r < unseenCounts.size && unseenCounts[r] >= 2) d++
                d
            }
            // 结构牌无法精确预判，给一个中位威胁，避免残局时全部堵死
            else -> 1
        }
        if (minOpponentCards == 1 && group.type == CardType.SINGLE && danger > 0) danger += 50
        return danger
    }

    /**
     * C. 绝对控牌判断（精确大牌身份）：对手剩余不可见牌中已无任何更高同型能压住该牌
     * 即依据「已出记录 + 自己手牌」反推出的最大剩余身份，确定此牌当前绝对通吃
     */
    private fun isUnbeatable(group: CardGroup, unseenCounts: IntArray): Boolean {
        return when (group.type) {
            CardType.SINGLE -> (group.mainRank + 1..17).none { r -> r < unseenCounts.size && unseenCounts[r] > 0 }
            CardType.PAIR -> (group.mainRank + 1..15).none { r -> r < unseenCounts.size && unseenCounts[r] >= 2 }
            else -> false
        }
    }

    /**
     * A. 残局 1 层递归必胜判定（启用条件：对手手数 <= 4 且本手剩余 <= 8，限定计算量）
     *
     * 枚举各领出候选，回推对手应对：
     *  - 领出即清空手牌 → 必然赢，直接返回；
     *  - 领出绝对通吃（不可被反制）→ 控制权笃定回到己方，剩余手牌可自行收完；
     *  - 否则对手可能接走后反身领出 → 仅当剩余手牌恰好拢成一整手（一手完）才视为必胜。
     * 按领出威胁度升序取第一个可靠的必胜出法（威胁最小的必胜优先）。
     */
    private fun canFinishGuaranteed(
        hand: List<Card>,
        validPlays: List<CardGroup>,
        minOpponentCards: Int,
        unseenCounts: IntArray
    ): CardGroup? {
        if (minOpponentCards > 4 || hand.size > 8) return null
        val candidates = validPlays
            .filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
            .sortedBy { feedDanger(it, unseenCounts, minOpponentCards) }
        for (c in candidates) {
            val remaining = hand.filter { card -> c.cards.none { it.id == card.id } }
            // 领出即清空手牌：必然直接获胜
            if (remaining.isEmpty()) return c
            val remTurns = estimateHandTurns(remaining)
            // 绝对通吃：对手只能过牌，控制权必回己方，剩余牌可自给收完
            if (isUnbeatable(c, unseenCounts)) {
                if (remTurns <= remaining.size) return c
                continue
            }
            // 对手能接：仅当剩余牌拢成一整手（再一手即走完），对手代行领出也无损
            if (remTurns == 1) return c
        }
        return null
    }
}
