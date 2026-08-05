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

    // ---- per-turn context set by decide(), used by all internal functions ----
    private var ctxRole: PlayerRole = PlayerRole.FARMER
    private var ctxTeammateCardCount: Int = 0
    private var ctxMinOpponentCards: Int = 20
    private var ctxMaxOpponentCards: Int = 20
    private var ctxUnseenCounts: IntArray = IntArray(18)
    private var ctxPerPlayerPlayed: Array<IntArray> = Array(3) { IntArray(18) }
    private var ctxPrimaryOpponent: Int = -1
    private var ctxTeammateIndex: Int = -1

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
     * @param perPlayerPlayed 三各玩家已打出的各 rank 张数
     * @param primaryOpponentIndex 主要对手索引（农场=地主，地主=下一张农民）
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
        // 保存本轮上下文供内部函数直接访问
        ctxRole = role
        ctxTeammateCardCount = teammateCardCount
        ctxMinOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min() else 20
        ctxMaxOpponentCards = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.max() else 20
        ctxUnseenCounts = unseenCounts
        ctxPerPlayerPlayed = perPlayerPlayed
        ctxPrimaryOpponent = primaryOpponentIndex
        ctxTeammateIndex = teammateIndex

        // 本轮首家：上一手为空，或上一手就是自己出的牌（即轮转回自己，应重新自由出牌）
        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            (myIndex >= 0 && lastPlayerIndex == myIndex)

        // 本轮首家必须出牌（自由在线），绝不压自己的牌，也绝不跳过
        return when (difficulty) {
            Difficulty.EASY -> if (isFreeLead) easyFreePlay(hand) else easyDecision(hand, lastPlay)
            Difficulty.NORMAL -> if (isFreeLead) {
                freePlayStrategy(
                    hand, CardRuleEngine.findAllValidPlays(hand, null), role, teammateCardCount,
                    opponentCardCounts, unseenCounts, myIndex, landlordIndex
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
            val pairKicker = counts.entries.firstOrNull { it.key != r && it.key !in tripleSet && it.value >= 2 && !isControlRank(it.key) }
                ?: counts.entries.firstOrNull { it.key != r && it.key !in tripleSet && it.value >= 2 }
            if (pairKicker != null) {
                counts[pairKicker.key] = pairKicker.value - 2
                if (counts[pairKicker.key] == 0) counts.remove(pairKicker.key)
            } else {
                val singleKicker = counts.entries.firstOrNull { it.key != r && it.key !in tripleSet && it.value >= 1 && !isControlRank(it.key) }
                    ?: counts.entries.firstOrNull { it.key != r && it.key !in tripleSet && it.value >= 1 }
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
            return freePlayStrategy(hand, validPlays, role, teammateCardCount, opponentCardCounts, unseenCounts, myIndex, landlordIndex)
        }

        // 跟牌策略（考虑竞合关系）
        return followPlayStrategy(hand, validPlays, lastPlay, role, teammateCardCount, lastPlayerIndex, myIndex, opponentCardCounts, landlordIndex, unseenCounts)
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
        unseenCounts: IntArray,
        myIndex: Int,
        landlordIndex: Int
    ): CardGroup {
        // 如果手牌只剩一手，直接出完
        val fullHand = CardRuleEngine.identify(hand)
        if (fullHand.type != CardType.INVALID) {
            return fullHand
        }

        // A. 整手分解规划（提前计算，供多个分支使用）
        val plan = buildHandPlan(hand)

        // 轮转位置：判断下一家是谁（喂牌时决定是否会被地主拦截）
        val minOpponentCards0 = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.min() else 20
        val teammateIndex = if (role == PlayerRole.FARMER)
            (0..2).firstOrNull { it != myIndex && it != landlordIndex } ?: -1
        else -1
        val nextIndex = if (myIndex >= 0) (myIndex + 1) % 3 else -1
        val teammateIsNext = myIndex >= 0 && nextIndex == teammateIndex

        // 农民配合 + B. 报单喂队友
        if (role == PlayerRole.FARMER && teammateCardCount in 1..3) {
            if (teammateCardCount == 1) {
                // 队友报单：领出最小非控单张，让队友好接走完
                val lead = plan.firstOrNull { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
                    ?: plan.firstOrNull { it.type == CardType.SINGLE }
                    ?: plan.firstOrNull { it.type == CardType.PAIR }
                if (lead != null) {
                    // 地主位于我与队友之间（队友不接牌）时，喂小单张易被地主拦截，
                    // 只有当该牌对手难压住（threat 低）才喂，否则交给常规领出
                    val riskyToFeed = !teammateIsNext && lead.type == CardType.SINGLE &&
                        feedDanger(lead) > 1
                    if (!riskyToFeed) return lead
                }
            } else if (teammateCardCount == 2) {
                // 队友剩2张：出大牌帮顶（同型内取最大）
                val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
                if (bigPlays.isNotEmpty()) {
                    return pickSinglePairBest(bigPlays, max = true)!!
                }
            } else {
                // 队友剩3张：出大单张/对子帮顶，为队友铺垫
                val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
                if (bigPlays.isNotEmpty()) {
                    return pickSinglePairBest(bigPlays, max = true)!!
                }
            }
        }

        // （已移除：地主残局"出最大"分支。该分支曾在必胜/残局策略之前触发，导致地主
        //  接近胜利时被引导先甩最大牌反而打输；手少时的正确领出交给下方残局与必胜逻辑）

        // 对手牌少时，出大牌压制（F：地主用2/王回手，此处保留大牌抢回控制）
        val minOpponentCards = minOpponentCards0

        // A. 残局必胜（1 层递归）：若能必然冲完，直接连出，不做其它权衡
        val guaranteed = canFinishGuaranteed(hand, validPlays)
        if (guaranteed != null) return guaranteed

        if (minOpponentCards <= 3) {
            // 若2/王等控牌全部已出（对手手里没有），则小牌也能控场，优先出最小的
            val controlUnseen = (15..17).sumOf { r -> if (r < unseenCounts.size) unseenCounts[r] else 0 }
            val bigPlays = validPlays.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
            if (bigPlays.isNotEmpty()) {
                return if (controlUnseen == 0) pickSinglePairBest(bigPlays, max = false)!!
                else pickSinglePairBest(bigPlays, max = true)!!
            }
        }

        // A. 残局前瞻（增强）：手牌较少时枚举领出，防喂杀 + 最小剩余手数 + 优先不可被反制的牌
        // 若出张牌后只剩炸弹/火箭且剩余能一把收走，炸弹开路是更优的路线
        if (hand.size <= 8) {
            data class Candidate(val group: CardGroup, val turns: Int, val danger: Int, val unbeatable: Boolean)
            val cands = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
                .map { c ->
                    val remaining = hand.filter { card -> c.cards.none { it.id == card.id } }
                    Candidate(
                        c,
                        estimateHandTurns(remaining),
                        feedDanger(c),
                        isUnbeatableOptimistic(c)
                    )
                }
                .toMutableList()
            // 炸弹开路：若出炸弹后剩余牌可一手出完（如炸弹+双王/炸弹+单张足够收），炸弹更优
            for (bomb in validPlays) {
                if (bomb.type != CardType.BOMB && bomb.type != CardType.ROCKET) continue
                val rem = hand.filter { card -> bomb.cards.none { it.id == card.id } }
                if (rem.isEmpty()) { cands.add(Candidate(bomb, 0, 0, true)); break }
                val id = CardRuleEngine.identify(rem)
                if (id.type != CardType.INVALID) {
                    cands.add(Candidate(bomb, 0, 0, true))
                    break
                }
            }
            if (cands.isNotEmpty()) {
                // 炸弹候选项 danger 已置零，turns=0 且 unbeatable 强置 true，
                // 排序规则中 danger 最低、unbeatable 优先的炸弹会天然排在前列
                val best = cands.minWithOrNull(
                    compareBy({ it.danger }, { if (it.unbeatable) 0 else 1 }, { it.turns }, { it.group.mainRank })
                )
                if (best != null) return best.group
            }
        }

        // D. 地主钓牌：手牌较优（较大且紧凑）且无必杀压力时，偶尔出中位诱饵勾出对手的2/王
        // 钓牌时机：手牌较多（>10张）时有空间耐心等待，且手牌紧凑（计划手数少）；
        // 仅在不落后时钓牌，落后时应专注甩牌减负而非试探
        val landlordGap = if (role == PlayerRole.LANDLORD) hand.size - minOpponentCards else 0
        if (role == PlayerRole.LANDLORD && hand.size > 10 && landlordGap < 6 && plan.size <= 6 && Math.random() < 0.30) {
            val bait = pickBait(plan)
            if (bait != null) return bait
        }

        // 地主落后（手牌明显多于最近农民）时激进甩牌：优先甩最大结构牌快速减负
        if (role == PlayerRole.LANDLORD && landlordGap >= 6 && hand.size > 8) {
            val structures = plan.filter {
                it.type == CardType.STRAIGHT || it.type == CardType.STRAIGHT_PAIR ||
                it.type == CardType.PLANE || it.type == CardType.PLANE_SINGLE || it.type == CardType.PLANE_PAIR ||
                it.type == CardType.TRIPLE || it.type == CardType.TRIPLE_ONE || it.type == CardType.TRIPLE_TWO
            }.sortedByDescending { it.size }
            if (structures.isNotEmpty()) return structures.first()
            val bigSinglePair = plan.filter { it.type == CardType.SINGLE || it.type == CardType.PAIR }
            if (bigSinglePair.isNotEmpty()) {
                return bigSinglePair.sortedWith(
                    compareBy({ it.mainRank }, { if (it.type == CardType.PAIR) 1 else 0 })
                ).last()
            }
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
                compareBy({ leadThreat(it) }, { it.mainRank })
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
        teammateCardCount: Int,
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

        // D. 位置修正：地主刚出完且队友（介于地主与我之间）已过牌、快出完时，
        // 不应无条件过牌送地主免费领出，而应尽量接过控制（交由下方普通跟牌/深度合作逻辑决定）
        if (role == PlayerRole.FARMER && landlordIndex == lastPlayerIndex) {
            // 深度合作：若我有便宜非控牌可接过，主动接过保护队友好走
            if (minOpponentCards > 3) {
                val cheap = normalPlays.filter { it.type == lastPlay.type && !isControlRank(it.mainRank) }
                if (cheap.isNotEmpty()) {
                    return chooseFollowPlay(cheap, lastPlay, hand)
                }
            }
            // 队友临近报单（剩1~2张）：即使要动用控牌也值得接过控制，为队友好走创造条件
            if (teammateCardCount in 1..2) {
                val anyFollow = normalPlays.filter { it.type == lastPlay.type }
                if (anyFollow.isNotEmpty()) {
                    return chooseFollowPlay(anyFollow, lastPlay, hand)
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
                higherOppRanks(lastPlay.type, lastPlay.mainRank) else 0
            if (role == PlayerRole.FARMER && higher > 2) {
                val nonControl = normalPlays.filter { it.type == lastPlay.type && !isControlRank(it.mainRank) }
                if (nonControl.isNotEmpty()) {
                    return chooseFollowPlay(nonControl, lastPlay, hand)
                }
                // 无法避免动用控牌时，只有在紧急情况下才打出控牌
                // 紧急情况：自己手牌较少（<=5），或队友手牌较少（<=3），或有队友获胜压力
                val isUrgent = hand.size <= 5 || teammateCardCount <= 3 || minOpponentCards <= 3
                if (!isUrgent) {
                    return null  // 非紧急时保留控牌
                }
                // 紧急情况下，使用最小的控牌
                val controlPlays = normalPlays.filter { it.type == lastPlay.type && isControlRank(it.mainRank) }
                if (controlPlays.isNotEmpty()) {
                    return chooseFollowPlay(controlPlays, lastPlay, hand)
                }
            }
            // 5. 主动过牌/控牌保留（地主）：评估跟牌后对手仍能反制的威胁，非紧急 → 保存控牌
            if (role == PlayerRole.LANDLORD) {
                val candidates = normalPlays.filter { it.type == lastPlay.type }
                if (candidates.isNotEmpty()) {
                    // 先用非控牌跟，若不存在非控牌，评估动用控牌的反制威胁
                    val nonControl = candidates.filter { !isControlRank(it.mainRank) }
                    if (nonControl.isNotEmpty()) {
                        return chooseFollowPlay(nonControl, lastPlay, hand)
                    }
                    // 所有可用跟牌都需要动用控牌，评估反制风险
                    val isUrgent = hand.size <= 4 || minOpponentCards <= 3
                    if (higher > 1 && !isUrgent) {
                        return null  // 对手仍有高牌能压回且非紧急，保留控牌
                    }
                    return chooseFollowPlay(candidates, lastPlay, hand)
                }
                return null // 没有合规跟牌则过牌
            }
            // B. 正常情况：成本aware选择（破坏结构最小、其次最小、控牌最后）
            return chooseFollowPlay(normalPlays, lastPlay, hand)
        }

        // E. 炸弹分层时机
        val bombs = validPlays.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }
        if (bombs.isNotEmpty() && !isTeammatePlayed) {
            return decideBomb(bombs, hand)
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
        if (hand.size <= 6) {
            return candidates.minWithOrNull(
                compareBy(
                    { if (isControlRank(it.mainRank)) 4 else 0 },
                    { estimateHandTurns(hand.filter { card -> it.cards.none { c -> c.id == card.id } }) },
                    { it.mainRank }
                )
            ) ?: candidates.minByOrNull { it.mainRank }
        }
        val plan = buildHandPlan(hand)
        if (ctxRole == PlayerRole.FARMER && ctxTeammateCardCount in 1..3) {
            return candidates.minWithOrNull(
                compareBy(
                    { followCost(it, plan) },
                    { followReBeatThreat(it) },
                    { it.mainRank }
                )
            ) ?: candidates.minByOrNull { it.mainRank }
        }
        return candidates.minWithOrNull(compareBy({ followCost(it, plan) }, { it.mainRank }))
            ?: normalPlays.minByOrNull { it.mainRank }
    }

    /**
     * 护队场景下跟牌的反制威胁：对手（地主）能压住该跟牌的更高同型 rank 数，越小越安全
     */
    private fun followReBeatThreat(group: CardGroup): Int {
        val raw = when (group.type) {
            CardType.SINGLE -> {
                var sum = 0
                for (r in (group.mainRank + 1)..17)
                    if (r < ctxUnseenCounts.size) sum += ctxUnseenCounts[r]
                sum
            }
            CardType.PAIR -> {
                var n = 0
                for (r in (group.mainRank + 1)..15)
                    if (r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 2) n++
                n
            }
            else -> 0
        }
        return scaledThreat(raw)
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
     * 农民视角的威胁折减： unseen 同时包含地主与队友的牌，而农民真正的对手只有地主。
     * 按「地主手牌数 / 地主+队友手牌数」比例 ，区域「能压住本手的高位牌数量」折减，
     * 避免把队友可能持牌也算作对手威胁而误判。
     * 地主没有队友，直接返回原值。
     * 改进：去掉 maxOf(1) 的下限强制，按真实比例计算（可到 0）避免过度保守。
     */
    private fun scaledThreat(count: Int): Int {
        if (ctxRole != PlayerRole.FARMER) return count
        val total = ctxMinOpponentCards + ctxTeammateCardCount
        if (total <= 0) return count
        if (count <= 0) return 0
        val scaled = count * ctxMinOpponentCards.toFloat() / total
        return Math.round(scaled)
    }

    /**
     * 在单张/对子候选中取最值：同型内比较 mainRank，避免「单张 A 与对子 K」混比大小；
     * max 时优先对子（两手并作一手、更不易被压），min 时优先更小的牌。
     */
    private fun pickSinglePairBest(candidates: List<CardGroup>, max: Boolean): CardGroup? {
        val singles = candidates.filter { it.type == CardType.SINGLE }
        val pairs = candidates.filter { it.type == CardType.PAIR }
        if (max) {
            // 取最大：跨型比较以 rank 为主（谁大谁更能控场），
            // 对子只在同 rank 时优先（一手清两张），避免为打对子而浪费更高单张
            return (singles + pairs).sortedWith(
                compareBy({ it.mainRank }, { if (it.type == CardType.PAIR) 1 else 0 })
            ).lastOrNull()
        }
        val minSingle = singles.minByOrNull { it.mainRank }
        val minPair = pairs.minByOrNull { it.mainRank }
        return when {
            minSingle != null && minPair == null -> minSingle
            minPair != null && minSingle == null -> minPair
            minSingle != null -> if (minSingle.mainRank <= minPair!!.mainRank) minSingle else minPair
            else -> null
        }
    }

    /**
     * A. 整手分解规划：把 17 张拆成近乎最少出牌组，输出「先出垃圾、后留武器」的有序计划
     */
    private fun buildHandPlan(hand: List<Card>): List<CardGroup> {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }.toMutableMap()
        val cardsByRank = hand.groupByTo(mutableMapOf()) { it.rank }
        val plan = mutableListOf<CardGroup>()
        val weapons = mutableListOf<CardGroup>()  // 炸弹/火箭收尾

        /** 从 cardsByRank 中切出 n 张，返回切出的牌并从池子中删除 */
        fun takeCards(r: Int, n: Int): List<Card> {
            val pool = cardsByRank.getValue(r)
            val taken = pool.take(n)
            cardsByRank[r] = pool.drop(n).toMutableList()
            return taken
        }

        // 火箭
        if ((counts[16] ?: 0) > 0 && (counts[17] ?: 0) > 0) {
            weapons.add(CardGroup(CardType.ROCKET, 17, 1, takeCards(16, 1) + takeCards(17, 1)))
            counts.remove(16)
            counts.remove(17)
        }
        // 炸弹
        val bombRanks = counts.filterValues { it == 4 }.keys.sorted()
        for (r in bombRanks) {
            weapons.add(CardGroup(CardType.BOMB, r, 1, takeCards(r, 4)))
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
            val three = takeCards(r, 3)
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
                pairs.add(CardGroup(CardType.PAIR, r, 1, takeCards(r, 2)))
                counts[r] = n - 2
            }
        }
        // 散单
        val singles = mutableListOf<CardGroup>()
        for ((r, n) in counts.entries.sortedBy { it.key }.toList()) {
            if (n >= 1) singles.add(CardGroup(CardType.SINGLE, r, 1, takeCards(r, 1)))
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
        cardsByRank: MutableMap<Int, MutableList<Card>>,
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

    /**
     * 抽取一段连续 rank 结构（各至少 m 张）为一手牌，并扣减计数
     */
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
                    val pool = cardsByRank.getValue(rk)
                    runCards.addAll(pool.take(m))
                    cardsByRank[rk] = pool.drop(m).toMutableList()
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

    /** C. 统计对手能压住该牌型的更高数量（按实际张数求和，经农民折减） */
    private fun higherOppRanks(groupType: CardType, mainRank: Int): Int {
        var count = 0
        when (groupType) {
            CardType.SINGLE -> for (r in (mainRank + 1)..17)
                if (r < ctxUnseenCounts.size) count += ctxUnseenCounts[r]
            CardType.PAIR -> for (r in (mainRank + 1)..15)
                if (r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 2) count++
            else -> {}
        }
        return scaledThreat(count)
    }

    /** E. 炸弹分层：防杀/收尾/救队友/安全翻倍才炸，不盲目概率炸 */
    private fun decideBomb(
        bombs: List<CardGroup>,
        hand: List<Card>
    ): CardGroup? {
        val b = bombs.maxByOrNull { it.mainRank } ?: return null
        // 是否存在对手手中可反制的更高炸弹/火箭（若有，炸完未必能守住控制权）
        // 取最大炸弹以最小化被反制风险
        val canBeCountered = hasUnseenHigherBombOrRocket(b.mainRank)
        // 1. 对手距获胜很近，必须炸阻止（即使可能被反也值得一搏）
        if (ctxMinOpponentCards <= 5) return b
        // 2. 出炸后剩余手牌若成一整手，炸完可收尾（前提：对方无反制炸弹时才稳）
        val remaining = hand.filter { c -> b.cards.none { it.id == c.id } }
        if (!canBeCountered && remaining.isNotEmpty() && remaining.size <= 5 &&
            CardRuleEngine.identify(remaining).type != CardType.INVALID) {
            return b
        }
        // 3. 农民炸救队友：队友接近报单时抢回控制权护送（对方无反制炸弹时更安全）
        if (ctxRole == PlayerRole.FARMER && ctxTeammateCardCount in 1..2 && !canBeCountered) return b
        // 4. 安全翻倍炸：对手手中无任何2/王、且无更高炸弹可反 → 绝对安全且能翻倍
        val rawControls = (15..17).sumOf { r -> if (r < ctxUnseenCounts.size) ctxUnseenCounts[r] else 0 }
        val controls = scaledThreat(rawControls)
        if (controls == 0 && !canBeCountered) return b
        // 其余情况保留炸弹，避免交回控制权
        return null
    }

    /** 2. 领出威胁度：对手能压住该单张/对子 - 按实际张数求和，越小越安全 */
    private fun leadThreat(group: CardGroup): Int {
        val raw = when (group.type) {
            CardType.SINGLE -> {
                var sum = 0
                for (r in (group.mainRank + 1)..17)
                    if (r < ctxUnseenCounts.size) sum += ctxUnseenCounts[r]
                sum
            }
            CardType.PAIR -> {
                var n = 0
                for (r in (group.mainRank + 1)..15)
                    if (r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 2) n++
                n
            }
            else -> 100
        }
        return if (raw >= 100) raw else scaledThreat(raw)
    }

    /** D. 地主钓牌：从计划里挑一手中位对子/单张作诱饵，勾出对手的2/王 */
    private fun pickBait(plan: List<CardGroup>): CardGroup? {
        val pairs = plan.filter { it.type == CardType.PAIR && !isControlRank(it.mainRank) }
        val singles = plan.filter { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
        if (Math.random() < 0.5 && pairs.isNotEmpty()) {
            return pairs.firstOrNull { it.mainRank in 9..13 } ?: pairs.minByOrNull { it.mainRank }
        }
        if (singles.isNotEmpty()) {
            return singles.firstOrNull { it.mainRank in 9..13 } ?: singles.minByOrNull { it.mainRank }
        }
        return null
    }

    /**
     * B. 领出威胁度（残局增强版）：单/对统计更高不可见同型数量；
     * 若对手报单（剩 1 张）且该单张能被接，则喂杀风险极大 → 大幅抬高威胁
     */
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

    /**
     * C. 绝对控牌判断（精确大牌身份）：对手剩余不可见牌中已无任何更高同型能压住该牌
     * 即依据「已出记录 + 自己手牌」反推出的最大剩余身份，确定此牌当前绝对通吃
     */
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

    /**
     * 计算 unseen 中起始 rank > minRank、连续长度达到 needPerRank 张的 run 的最大长度
     * 用于判断结构牌（顺子/连对/飞机）是否可被更高同型压住
     */
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

    /**
     * 残局软偏好用的「乐观不可被压」：农民视角下，若更高同型经队友折减后威胁为 0，
     * 则可能是队友持有而实际无人可压，可在残局选牌时作为偏好（非必胜保证）。
     * 仅用于自由领出的候选排序；必胜判定 canFinishGuaranteed 仍用严格的 isUnbeatable。
     */
    private fun isUnbeatableOptimistic(group: CardGroup): Boolean {
        return when (group.type) {
            CardType.SINGLE -> {
                val high = (group.mainRank + 1..17).count { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] > 0 }
                scaledThreat(high) == 0
            }
            CardType.PAIR -> {
                val high = (group.mainRank + 1..15).count { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 2 }
                scaledThreat(high) == 0
            }
            CardType.STRAIGHT ->
                scaledThreat(maxUnseenRun(group.mainRank, 1)) < group.length
            CardType.STRAIGHT_PAIR ->
                scaledThreat(maxUnseenRun(group.mainRank, 2)) < group.length
            CardType.PLANE, CardType.PLANE_SINGLE, CardType.PLANE_PAIR ->
                scaledThreat(maxUnseenRun(group.mainRank, 3)) < group.length
            CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO -> {
                val high = (group.mainRank + 1..15).count { r -> r < ctxUnseenCounts.size && ctxUnseenCounts[r] >= 3 }
                scaledThreat(high) == 0
            }
            else -> false
        }
    }

    /**
     * A. 残局必胜判定（启用条件：对手手数 <= 4 且本手剩余 <= 8，限定计算量）
     *
     * 枚举各领出候选，回推对手应对：
     *  - 领出即清空手牌 → 必然赢，直接返回；
     *  - 领出绝对通吃（不可被反制）→ 控制权笃定回到己方，剩余牌须能「持续控场」地收完
     *    （递归 canWinInControl），才能保证必胜。
     * 注意：对手手中若仍有炸弹/火箭，任何非炸弹领出都可能被反制，则不作必胜判定。
     * 按领出威胁度升序取第一个可靠的必胜出法（威胁最小的必胜优先）。
     */
    private fun canFinishGuaranteed(
        hand: List<Card>,
        validPlays: List<CardGroup>
    ): CardGroup? {
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

    /**
     * 递归判断「已握控制权」时，能否用一系列绝对通吃的领出把牌收完。
     * 仅考虑不可被反制的领出（每一步都保住控制权），深度受限防爆栈。
     */
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

    /**
     * 判断对手手中是否仍有能反制 myBombRank 的更高炸弹或火箭（per-player 精确版）
     */
    private fun hasUnseenHigherBombOrRocket(myBombRank: Int): Boolean {
        if (ctxUnseenCounts.size < 18) return false
        // 火箭判断：若双王unseen，任一对手手牌 >= 2 即可能持火箭
        if (ctxUnseenCounts[16] == 1 && ctxUnseenCounts[17] == 1 && ctxMaxOpponentCards >= 2) {
            return true
        }
        for (r in (myBombRank + 1)..15) {
            if (ctxUnseenCounts[r] == 4) return true
        }
        return false
    }

    /**
     * 判断 unseen 中是否仍存在对手可能持有的炸弹/火箭（任一 rank 4 张齐全，或双王齐全）
     * 若有，则「非炸弹领出」并非绝对安全，必胜循环需谨慎
     */
    private fun hasUnseenBombOrRocket(): Boolean {
        if (ctxUnseenCounts.size < 18) return false
        // 双王 unseen 且任一对手有 >=2 手牌可容火箭
        if (ctxUnseenCounts[16] == 1 && ctxUnseenCounts[17] == 1 && ctxMaxOpponentCards >= 2) {
            return true
        }
        for (r in 3..15) if (ctxUnseenCounts[r] == 4) return true
        return false
    }
}
