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
    /** 各敌人当前剩余牌数（农民视角=[地主]；地主视角=[农民1,农民2]），用于按对手分别推演 */
    private var ctxEnemyTotals: IntArray = intArrayOf()
    private var ctxUnseenCounts: IntArray = IntArray(18)
    private var ctxPerPlayerPlayed: Array<IntArray> = Array(3) { IntArray(18) }
    private var ctxPrimaryOpponent: Int = -1
    private var ctxTeammateIndex: Int = -1

    /** 控牌：2(15)/小王(16)/大王(17)，用于回收控制权，尽量后手使用 */
    private val ControlRanks = setOf(15, 16, 17)

    /**
     * 跟牌时"大单张"（A/2/小王/大王）出牌阈值：
     * 仅当对手出的单张 >= 此点数时才允许用大单张去压，避免为小牌保结构/压队友而浪费大牌。
     */
    private val BIG_SINGLE_FOLLOW_MIN_RANK = 14

    /**
     * 对手出单张/对子时，允许"拆牌组去跟"（拆对/三张/顺子/连对/飞机）的最低牌面：
     * 仅当对手牌面 >= 此点数(Q=12)才允许拆牌去跟；否则只用真散牌跟。队友出牌始终不拆。
     */
    private val OPPONENT_BREAK_MIN_RANK = 12

    private fun isControlRank(rank: Int): Boolean = rank in ControlRanks

    /**
     * 该组合是否拆散手中的炸弹或王炸（双王）：
     * 正常出牌/跟牌/领出时，炸弹是宝贵的控牌武器，任何拆解（拆1张变三张、
     * 拆2张变对子、拆3张变废牌）都视为破坏而禁止；只有 BOMB/ROCKET 本尊可用。
     * 必胜判定等少数场景用 breaksBombForWin 放宽。
     */
    private fun breaksBomb(group: CardGroup, hand: List<Card>): Boolean {
        if (group.type == CardType.BOMB || group.type == CardType.ROCKET) return false
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        // 王炸保护：手中有双王，但组合（非火箭）用了其中一张王
        val hasSmallJoker = counts[16] ?: 0 > 0
        val hasBigJoker = counts[17] ?: 0 > 0
        if (hasSmallJoker && hasBigJoker) {
            if (group.cards.any { it.rank == 16 || it.rank == 17 }) return true
        }
        val used = group.cards.groupBy { it.rank }.mapValues { it.value.size }
        for ((rank, _) in used) {
            val own = counts[rank] ?: 0
            // 用了炸弹 rank 的任何一张即视为拆弹
            if (own == 4) return true
        }
        return false
    }

    /**
     * 必胜判定专用放宽版：允许为「保证赢牌」牺牲炸弹——拆1张（剩三张）或拆2张
     * （剩对子）仍留有可复用牌组，不算破坏；仅拆3张（剩1张废牌彻底报废）与拆王炸禁止。
     */
    private fun breaksBombForWin(group: CardGroup, hand: List<Card>): Boolean {
        if (group.type == CardType.BOMB || group.type == CardType.ROCKET) return false
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        // 王炸保护：手中有双王，但组合（非火箭）用了其中一张王
        val hasSmallJoker = counts[16] ?: 0 > 0
        val hasBigJoker = counts[17] ?: 0 > 0
        if (hasSmallJoker && hasBigJoker) {
            if (group.cards.any { it.rank == 16 || it.rank == 17 }) return true
        }
        val used = group.cards.groupBy { it.rank }.mapValues { it.value.size }
        for ((rank, n) in used) {
            val own = counts[rank] ?: 0
            // 4张全留下时才构成炸弹；用了3张=炸完剩1张废牌，彻底报废
            if (own == 4 && n == 3) return true
        }
        return false
    }

    /**
     * 这手牌是否会拆散手上的完整牌组（对子/三张/炸弹等）：
     * 只在拆完只剩 1 张散牌（无法再组成对子/三张等结构）时才算破坏；
     * 反之从三张拆1张（剩对子仍完整）、从炸弹拆1/2张（剩三张/对子仍完整）不算拆。
     */
    private fun preservesGroups(cards: List<Card>, hand: List<Card>): Boolean {
        val orig = hand.groupBy { it.rank }.mapValues { it.value.size }
        for (card in cards) {
            val o = orig[card.rank] ?: 0
            if (o >= 2) {
                val used = cards.count { it.rank == card.rank }
                if (o - used == 1) return false
            }
        }
        // 出单张若拆了顺子/连对/飞机，也算破坏结构，优先用真散牌
        if (cards.size == 1 && isStraightMember(cards[0].rank, hand)) return false
        return true
    }

    /**
     * 某点数是否处于"每点至少 m 张、长度>=minLen"的连通段：
     *   m=1,minLen=5 → 顺子；m=2,minLen=3 → 连对；m=3,minLen=2 → 飞机。
     * 用于判断"出这张牌会否拆掉一个结构牌组"。
     */
    private fun inRunSegment(rank: Int, hand: List<Card>, m: Int, minLen: Int): Boolean {
        if (rank !in 3..14) return false
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        if ((counts[rank] ?: 0) < m) return false
        var len = 1
        var r = rank - 1
        while (r in 3..14 && (counts[r] ?: 0) >= m) { len++; r-- }
        r = rank + 1
        while (r in 3..14 && (counts[r] ?: 0) >= m) { len++; r++ }
        return len >= minLen
    }

    /** 某点数是否处于一个长度>=5 的顺子段中（单张跟牌时判断会否拆顺子） */
    private fun isStraightMember(rank: Int, hand: List<Card>): Boolean =
        inRunSegment(rank, hand, 1, 5)

    /** 某点数是否处于一个长度>=3 的连对段中（跟对子时判断会否拆连对） */
    private fun isStraightPairMember(rank: Int, hand: List<Card>): Boolean =
        inRunSegment(rank, hand, 2, 3)

    /** 某点数是否处于一个长度>=2 的飞机段中（跟三张时判断会否拆飞机） */
    private fun isPlaneMember(rank: Int, hand: List<Card>): Boolean =
        inRunSegment(rank, hand, 3, 2)

    /**
     * 跟单张时，出这张单张对原有牌组的"破坏等级"：
     *   0 = 真散牌（该点数仅 1 张），最优，优先用于跟牌；
     *   1 = 拆对子/三张（含飞机中的三张），损失相对较小，允许拆出单张压制；
     *   2 = 拆顺子/连对/飞机等长结构，破坏代价高，仅在紧急时才允许。
     * 注：处于长顺子段的对子(如 3,3,4,5,6,7)按 2 处理——保顺子优先于保对子。
     */
    private fun singleDisruption(rank: Int, hand: List<Card>): Int {
        val n = hand.count { it.rank == rank }
        return when {
            n == 1 -> 0
            isStraightMember(rank, hand) -> 2
            n == 2 || n == 3 -> 1
            else -> 2
        }
    }

    /**
     * 跟任意牌型时，该手牌对原有牌组的"破坏等级"（0=不破坏, 1=拆对/三张, 2=拆顺子/连对/飞机）。
     * 用于跟牌选牌时优先保留牌组：先出散牌/完整牌组，再考虑拆对/三张，最后(紧急)才拆长结构。
     */
    private fun groupDisruption(g: CardGroup, hand: List<Card>): Int {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        return when (g.type) {
            CardType.SINGLE -> singleDisruption(g.mainRank, hand)
            CardType.PAIR -> {
                val n = counts[g.mainRank] ?: 0
                when {
                    n == 2 -> 0
                    isStraightPairMember(g.mainRank, hand) || isPlaneMember(g.mainRank, hand) -> 2
                    n == 3 -> 1
                    else -> 2
                }
            }
            CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO -> {
                val n = counts[g.mainRank] ?: 0
                when {
                    n == 3 -> 0
                    isPlaneMember(g.mainRank, hand) -> 2
                    else -> 0
                }
            }
            else -> 0 // 顺子/连对/飞机/炸弹/火箭：本身即结构牌，不再计破坏
        }
    }

    /**
     * 跟牌选牌：按"破坏等级"分档（不破坏 > 拆对/三张 > 拆顺子/连对/飞机），档内取最小点数；
     * 对所有牌型生效。非紧急时不拆长结构(等级2)，直接放过保存牌组。大单张(A/2/王)已在
     * sameTypeSafe 中按 allowBigSingleFollow 过滤过，这里不再处理。
     */
    /** 手中 A~王 的总分：A=2, 2=4, 小王=6, 大王=6（用于"是否值得拆牌跟"的动态评分） */
    private fun bigCardScore(hand: List<Card>): Int =
        hand.sumOf { c -> when (c.rank) {
            14 -> 2
            15 -> 4
            16 -> 6
            17 -> 6
            else -> 0
        } }

    /** 拆牌代价：拆三张(3张)比拆对子(2张)更亏，用于同档内优先拆更便宜的牌组 */
    private fun breakCost(g: CardGroup, hand: List<Card>): Int {
        val n = hand.count { it.rank == g.mainRank }
        return if (n >= 3) 2 else 1
    }

    /**
     * 跟牌选牌：按"破坏等级"分档（不破坏 > 拆对/三张 > 拆顺子/连对/飞机），档内优先拆便宜的牌组、再取最小点数。
     * 单张/对子跟牌的"拆牌"受两道闸：
     *   1) 对手牌面 >= Q(12) 才允许拆（OPPONENT_BREAK_MIN_RANK）；
     *   2) 拆对/三张时再按动态评分：对手剩牌 <=4（很近）或 大牌总分*2 >= 对手剩牌*3（≈ 大牌分 >= 1.5×剩牌）才拆。
     * 其余牌型维持原 isUrgent 逻辑。
     */
    private fun pickFollow(hand: List<Card>, candidates: List<CardGroup>, lastPlay: CardGroup): CardGroup? {
        if (candidates.isEmpty()) return null
        val allowBreak = lastPlay.mainRank >= OPPONENT_BREAK_MIN_RANK
        val oppLeft = ctxMinOpponentCards
        val myBig = bigCardScore(hand)
        val maxTierOther = if (isUrgent()) 2 else 1
        return candidates
            .filter { g ->
                val tier = groupDisruption(g, hand)
                when {
                    lastPlay.type != CardType.SINGLE && lastPlay.type != CardType.PAIR ->
                        tier <= maxTierOther
                    tier == 0 -> true                                  // 真散牌永远可跟
                    !allowBreak -> false                               // 对手牌面 < Q：绝不拆
                    tier == 1 -> oppLeft <= 4 || myBig * 2 >= oppLeft * 3   // 拆对/三张：很近 或 大牌足够
                    else -> true                                       // tier2 拆长结构：>=Q 即放行
                }
            }
            .minWithOrNull(compareBy(
                { groupDisruption(it, hand) },
                { breakCost(it, hand) },
                { it.mainRank }
            ))
    }

    private fun isUrgent(): Boolean =
        ctxMinOpponentCards <= 3 || ctxTeammateCardCount in 1..2

    /**
     * 跟牌（单张）时是否允许动用"大单张"（A/2/王, rank >= BIG_SINGLE_FOLLOW_MIN_RANK）：
     * 仅当满足以下任一条件才动用，否则保留大牌——
     *   1) 对手出的单张本身已 >= 阈值（值得用大牌去压）；
     *   2) 对手即将获胜（剩 <=2 张，必须拦截）。
     * 不满足时宁可放过，避免"为小牌保结构"或"浪费大牌压小单张"而消耗宝贵控牌。
     * 注：压队友的场景已在 shouldInterceptTeammate 中单独处理，这里遇到的一定是压对手。
     */
    private fun allowBigSingleFollow(lastPlay: CardGroup): Boolean {
        if (lastPlay.type != CardType.SINGLE) return true
        if (lastPlay.mainRank >= BIG_SINGLE_FOLLOW_MIN_RANK) return true
        if (ctxMinOpponentCards <= 2) return true // 对手即将获胜，必须拦截
        return false
    }

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
        ctxEnemyTotals = if (opponentCardCounts.isNotEmpty()) opponentCardCounts.copyOf() else intArrayOf()
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
        //    但对手(地主)也仅剩1张时不要喂——会把出牌权送给地主让他直接获胜
        if (role == PlayerRole.FARMER && ctxTeammateCardCount in 1..2 && ctxMinOpponentCards > 1) {
            val feed = feedTeammate(hand)
            if (feed != null) return feed
        }

        // 2. 残局必胜：能确保冲完就直接冲
        canFinishGuaranteed(hand, validPlays)?.let { return it }

        // 2.5 对手仅剩1张：控场甩牌组，大单张收尾，防被接走获胜
        if (ctxMinOpponentCards == 1) return endgameLeadAgainstOneCard(hand, validPlays)

        // 3. 手牌少：残局枚举，最小剩余手数 + 防喂杀
        if (hand.size <= 5) return endgameLead(hand, validPlays)

        // 4. 常规领出：小牌先行
        return chooseLead(hand, validPlays)
    }

    /** 队友剩1~2张时的喂牌：出最小的非控单张/对子，让队友好接走 */
    private fun feedTeammate(hand: List<Card>): CardGroup? {
        val plan = buildPlan(hand)
        if (ctxTeammateCardCount == 1) {
            // 队友报单：优先出最小非控单张助攻，让队友好接走获胜
            val single = plan.firstOrNull { it.type == CardType.SINGLE && !isControlRank(it.mainRank) }
                ?: plan.firstOrNull { it.type == CardType.SINGLE }
            if (single != null) return single
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
        // 进入此分支时 ctxMinOpponentCards 必然 > 1（==1 已在 normalFreeLead 的
        // step 2.5 提前返回 endgameLeadAgainstOneCard），故无需再判断报单。
        val candidates = validPlays.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET && !breaksBomb(it, hand) }
        // 残局领出：先甩非控牌，保留2/王后收尾。不能按 feedDanger 择优——
        // 控牌不可被压 feedDanger 恒为0，会领出就甩掉大王。故选剩余手数最少、点数最小的非控出法
        val shed = candidates.filter { !isControlRank(it.mainRank) }
        val pool = if (shed.isNotEmpty()) shed else candidates
        val best = pool.minWithOrNull(
            compareBy(
                { estimateHandTurns(hand.filter { c -> it.cards.none { x -> x.id == c.id } }) },
                { it.mainRank }
            )
        )
        return best
            ?: validPlays.firstOrNull { it.type == CardType.BOMB || it.type == CardType.ROCKET }
            ?: validPlays.first()
    }

    /**
     * 对手仅剩1张时的残局领出（实现「先出牌组、再大单张收尾」）。
     *
     * 对手只有1张，只能压「单张/对子」，**永远接不住任何多张结构牌（顺子/连对/飞机/三带）**，
     * 也接不住对子与炸弹/火箭，因此：
     *  1. 优先出「安全牌组」（非单张、不拆弹：含对子/结构/炸弹/火箭），对手必过牌，我方持续控场甩牌；
     *  2. 组牌择优：出完「剩余手数最少 + 散单最少」的牌组，并尽量消耗小牌（mainRank 小的结构），
     *     把大牌留作单张，避免最后被迫出小单张被对手那1张接走获胜；
     *  3. 仅剩单张时，从大到小领出——若对手那1张小于我最大单张则被顶死过牌，我能再多甩一张。
     */
    private fun endgameLeadAgainstOneCard(hand: List<Card>, validPlays: List<CardGroup>): CardGroup {
        val safe = validPlays.filter {
            it.type != CardType.SINGLE && !breaksBomb(it, hand)
        }
        if (safe.isNotEmpty()) {
            // 优先用 chooseStructureLead 的「拆分变体 + 剩余手数最少 + 散单最少」优化器选出最优结构牌，
            // 它已考虑「吸收小牌、保留大单张」，比下面简版比较更精细。该结构必然非单张、不拆弹，
            // 且对手仅1张永远接不住，可安全甩出持续控场。
            val bestStructure = chooseStructureLead(hand)
            if (bestStructure != null) return bestStructure
            // 退化情况（手中无任何结构牌，仅剩对子/炸弹等）：在 safe 中按
            // 「保留炸弹 + 剩余手数最少 + 散单最少 + 消耗小牌」择优
            return safe.minWithOrNull(
                compareBy(
                    { it.type == CardType.BOMB || it.type == CardType.ROCKET },        // 保留炸弹，优先普通牌组
                    { estimateHandTurns(hand.filter { c -> it.cards.none { x -> x.id == c.id } }) }, // 出完剩余手数最少
                    { scatteredSingles(hand.filter { c -> it.cards.none { x -> x.id == c.id } }) },   // 散单最少
                    { it.mainRank }                                                    // 消耗小牌、保留大单张
                )
            )!!
        }
        // 无安全牌组：只剩单张，从大到小领出
        val single = validPlays
            .filter { it.type == CardType.SINGLE && !breaksBomb(it, hand) }
            .maxByOrNull { it.mainRank }
        if (single != null) return single
        // 极端兜底（仅剩炸弹/拆弹单张）：出最大可用
        return validPlays.maxByOrNull { it.mainRank } ?: validPlays.first()
    }

    /**
     * 常规领出选择：
     *  A. 对手报单（1张）：优先甩结构/对子（防被接），只能出单张时出最大
     *  B. 甩结构牌（顺子/连对/飞机/三带）：一次减负多张，先甩威胁低的
     *  C. 出最小非控单张/对子作诱饵，大牌后手回收
     */
    private fun chooseLead(hand: List<Card>, validPlays: List<CardGroup>): CardGroup {
        val plan = buildPlan(hand)

        // 进入此分支时 ctxMinOpponentCards 必然 > 1（==1 已在 normalFreeLead 的
        // step 2.5 提前返回 endgameLeadAgainstOneCard），故无需再判断报单。

        // B. 结构牌优先：枚举拆分变体（短顺子/短连对/飞机/三带），
        //    选「出完剩余手数最少 + 威胁最低」的一手，减少零散单张
        chooseStructureLead(hand)?.let { return it }

        // C. 最小非控单张/对子作诱饵（保留2/王后手回收）
        val bait = plan.filter {
            (it.type == CardType.SINGLE || it.type == CardType.PAIR) && !isControlRank(it.mainRank)
        }
        if (bait.isNotEmpty()) {
            // 优先选「被压后仍可用大牌回收」的散牌作诱饵，形成「小牌-大牌回收-继续打」的节奏；
            // 否则只能走不可回收的散牌
            val reclaimable = bait.filter { canReclaimAfter(it, hand) }
            val pool = if (reclaimable.isNotEmpty()) reclaimable else bait
            return pool.minWithOrNull(compareBy({ it.mainRank }, { if (it.type == CardType.PAIR) 1 else 0 }))!!
        }

        // D. 兜底：非控最小出牌，不白白浪费2/王（也不拆弹）
        val nonBomb = validPlays.filter {
            it.type != CardType.BOMB && it.type != CardType.ROCKET && !isControlRank(it.mainRank) && !breaksBomb(it, hand)
        }
        return (nonBomb.minByOrNull { it.mainRank }
            ?: validPlays.firstOrNull { it.type != CardType.BOMB && it.type != CardType.ROCKET && !breaksBomb(it, hand) }
            ?: validPlays.firstOrNull { it.type == CardType.BOMB || it.type == CardType.ROCKET }
            ?: validPlays.first())
    }

    private fun isStructure(g: CardGroup): Boolean = when (g.type) {
        CardType.STRAIGHT, CardType.STRAIGHT_PAIR, CardType.PLANE, CardType.PLANE_SINGLE,
        CardType.PLANE_PAIR, CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO -> true
        else -> false
    }

    /**
     * 结构牌领出择优：
     * 枚举长顺子/长连对/长飞机的所有「拆分变体」及三带，计算每种出完后的「剩余手数」，
     * 选剩余手数最少、威胁最低的组合。目标是让零散小牌尽量被结构/带牌吸收，
     * 而不是一味追求一次甩出单张最多（可能反而在剩一堆小单张）。
     */
    private fun chooseStructureLead(hand: List<Card>): CardGroup? {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val bombRanks = counts.filterValues { it >= 4 }.keys
        val candidates = mutableListOf<CardGroup>()

        // 顺子（3..14，避开炸弹 rank，避免拆炸弹）
        val singleRanks = counts.keys.filter { it in 3..14 && it !in bombRanks }.sorted()
        addRunVariants(hand, singleRanks, 1, 5, CardType.STRAIGHT, candidates)
        // 连对（>=3连）
        val pairRanks = counts.filterValues { it >= 2 }.keys.filter { it in 3..14 && it !in bombRanks }.sorted()
        addRunVariants(hand, pairRanks, 2, 3, CardType.STRAIGHT_PAIR, candidates)
        // 飞机（>=2连）：纯飞机 + 带单翼/双翼，翼用最小非控散牌吸收零散小牌
        val tripleRanks = counts.filterValues { it >= 3 }.keys.filter { it in 3..14 && it !in bombRanks }.sorted()
        val planes = mutableListOf<CardGroup>()
        addRunVariants(hand, tripleRanks, 3, 2, CardType.PLANE, planes)
        for (plane in planes) {
            candidates.add(plane)
            val usedRanks = plane.cards.map { it.rank }.toSet()
            val wingLen = plane.length
            // 可用作翼的候选：非控、非炸弹、非飞机自身 rank；且不用 A(14)/2/王 当附件（多留大牌）
            val wingRanks = counts.entries
                .filter { it.key !in usedRanks && it.key !in bombRanks && !isControlRank(it.key) && it.key < 14 }
                .sortedBy { it.key }
            // 带单翼：优先用「真散牌」(仅1张、非顺子成员) 作翼吸收零散小牌，不足再从最小非顺子牌拆单张补齐
            if (wingRanks.size >= wingLen) {
                val pureSingles = wingRanks.filter { it.value == 1 && !isStraightMember(it.key, hand) }.take(wingLen)
                val wings = if (pureSingles.size >= wingLen) {
                    pureSingles.map { (rank, _) -> hand.first { it.rank == rank } }
                } else {
                    wingRanks.filter { !isStraightMember(it.key, hand) }.take(wingLen)
                        .flatMap { (rank, _) -> hand.filter { it.rank == rank }.take(1) }
                }
                candidates.add(CardGroup(CardType.PLANE_SINGLE, plane.mainRank, wingLen, plane.cards + wings))
            }
            // 带双翼：仅用「干净低对」(仅2张、<10、且非连对/飞机成员) 作翼；
            //         不够或只有 >=10 对子/结构对子时，一律不白送，只保留上面的带单翼方案。
            val pairWingRanks = wingRanks.filter {
                it.value == 2 && it.key < 10
                    && !isStraightPairMember(it.key, hand) && !isPlaneMember(it.key, hand)
            }.take(wingLen)
            if (pairWingRanks.size >= wingLen) {
                val pairWings = pairWingRanks.flatMap { (rank, _) -> hand.filter { it.rank == rank }.take(2) }
                candidates.add(CardGroup(CardType.PLANE_PAIR, plane.mainRank, wingLen, plane.cards + pairWings))
            }
        }

        // 三带一/三带二/三张：结构感知的 kicker 选择
        // 规则：
        //   - <10 的「干净对子」(仅2张、且不是连对/飞机/顺子成员) 优先作带对；
        //   - >=10 的对子、或任何结构成员(连对/飞机/顺子/三张) 一律不许当补牌；
        //   - 只要存在可带的「真散牌」(仅1张、非顺子成员)，就优先带单张小散牌，不白送 >=10 的对子。
        val looseSingleKicker = counts.entries
            .filter { it.key !in bombRanks && it.key < 14 && it.value == 1 && !isStraightMember(it.key, hand) }
            .minByOrNull { rankOf(it.key) }?.key
        val cleanLowPairKicker = counts.entries
            .filter { it.key !in bombRanks && it.value == 2 && it.key < 10
                && !isStraightPairMember(it.key, hand) && !isPlaneMember(it.key, hand) }
            .minByOrNull { rankOf(it.key) }?.key
        val highPairKicker = counts.entries
            .filter { it.key !in bombRanks && it.value == 2 && it.key >= 10
                && !isStraightPairMember(it.key, hand) && !isPlaneMember(it.key, hand) }
            .minByOrNull { rankOf(it.key) }?.key

        for (r in tripleRanks) {
            val three = hand.filter { it.rank == r }.take(3)
            // 优先带单张小散牌
            if (looseSingleKicker != null) {
                val kick = hand.first { it.rank == looseSingleKicker }
                candidates.add(CardGroup(CardType.TRIPLE_ONE, r, 1, three + kick))
            }
            // 带对：<10 干净对子优先；仅当没有任何散牌可带时，才退而用 >=10 的对子
            when {
                cleanLowPairKicker != null -> {
                    val kickers = hand.filter { c -> c.rank == cleanLowPairKicker }.take(2)
                    candidates.add(CardGroup(CardType.TRIPLE_TWO, r, 1, three + kickers))
                }
                highPairKicker != null && looseSingleKicker == null -> {
                    val kickers = hand.filter { c -> c.rank == highPairKicker }.take(2)
                    candidates.add(CardGroup(CardType.TRIPLE_TWO, r, 1, three + kickers))
                }
                looseSingleKicker == null && cleanLowPairKicker == null && highPairKicker == null -> {
                    candidates.add(CardGroup(CardType.TRIPLE, r, 1, three))
                }
            }
        }

        if (candidates.isEmpty()) return null

        // 评估：剩余手数最少优先，其次带走低牌(3..7)越多越好，其次零散单张最少，其次威胁最低，其次主 rank
        return candidates.minWithOrNull(
            compareBy(
                { estimateHandTurns(hand.filter { c -> it.cards.none { x -> x.id == c.id } }) },
                { -lowAbsorbed(it.cards) },
                { scatteredSingles(hand.filter { c -> it.cards.none { x -> x.id == c.id } }) },
                { feedDanger(it) },
                { it.mainRank }
            )
        )
    }

    /** 生成某类型连通段的所有长度变体（如7连顺子 → 所有5/6/7连子串） */
    private fun addRunVariants(
        hand: List<Card>,
        ranks: List<Int>,
        m: Int,
        minLen: Int,
        type: CardType,
        out: MutableList<CardGroup>
    ) {
        if (ranks.size < minLen) return
        var i = 0
        while (i < ranks.size) {
            var j = i
            while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
            val segLen = j - i + 1
            if (segLen >= minLen) {
                for (len in minLen..segLen) {
                    for (start in i..(j - len + 1)) {
                        val subRanks = ranks.subList(start, start + len)
                        val cards = subRanks.flatMap { r -> hand.filter { it.rank == r }.take(m) }
                        if (cards.size == len * m) {
                            out.add(CardGroup(type, ranks[start], len, cards))
                        }
                    }
                }
            }
            i = j + 1
        }
    }

    /** 出牌优先级排序：先非控小牌（利于当诱饵/吸收），控牌(2/王)殿后 */
    private fun rankOf(rank: Int): Int = if (isControlRank(rank)) 1000 + rank else rank

    /** 统计手中难以配对的零散单张数（单张、以及凑不成连对的对子），越少越不卡手 */
    private fun scatteredSingles(hand: List<Card>): Int {
        if (hand.isEmpty()) return 0
        val counts = hand.filter { it.rank in 3..14 }.groupBy { it.rank }.mapValues { it.value.size }
        var scattered = 0
        for ((rank, n) in counts) {
            when {
                n == 1 -> scattered++
                n == 2 -> if (!isNearPair(rank, counts)) scattered++ // 孤对也不易消化
                else -> {}
            }
        }
        return scattered
    }

    /** 某对子的相邻点（rank±1）也至少有1张，可参与连对，视为易组合 */
    private fun isNearPair(rank: Int, counts: Map<Int, Int>): Boolean =
        counts.containsKey(rank - 1) || counts.containsKey(rank + 1)

    /** 一手牌消耗的低点数牌(3..7)张数：越多说明把小散牌(34567)带走得越干净，用于结构领出择优 */
    private fun lowAbsorbed(cards: List<Card>): Int =
        cards.count { it.rank in 3..7 }

    /** 领出小散牌被压后，手中是否仍有大牌(2/王炸/炸弹)可回收控制权 */
    private fun canReclaimAfter(group: CardGroup, hand: List<Card>): Boolean {
        val remaining = hand.filter { c -> group.cards.none { it.id == c.id } }
        return remaining.any { it.rank == 15 } ||
            (remaining.any { it.rank == 16 } && remaining.any { it.rank == 17 }) ||
            remaining.groupBy { it.rank }.any { it.value.size == 4 }
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

        // 1. 队友出的牌：大牌不压 | 快赢接管 | 否则小牌顶着消耗
        if (role == PlayerRole.FARMER && isTeammate(lastPlayerIndex, myIndex, role, landlordIndex)) {
            return shouldInterceptTeammate(hand, validPlays, lastPlay)
        }

        val sameType = validPlays.filter { it.type == lastPlay.type && !breaksBomb(it, hand) }
        val nonControl = sameType.filter { !isControlRank(it.mainRank) }
        // 大单张克制：仅当允许时才把 A/2/王 纳入"可跟"候选，否则保留（见 allowBigSingleFollow）
        val allowBig = allowBigSingleFollow(lastPlay)
        val gateBig: (CardGroup) -> Boolean = { g ->
            !( !allowBig && g.type == CardType.SINGLE && g.mainRank >= BIG_SINGLE_FOLLOW_MIN_RANK )
        }
        val sameTypeSafe = sameType.filter(gateBig)
        val teammateIsNext = role == PlayerRole.FARMER && myIndex >= 0 && (myIndex + 1) % 3 == ctxTeammateIndex
        val landlordPlayed = role == PlayerRole.FARMER && lastPlayerIndex == landlordIndex
        val myLastResponder = landlordPlayed && !teammateIsNext

        // 2. 对手快赢了，必须压住（此处 ctxMinOpponentCards<=2，allowBig 必为 true，大单张可动用）
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

        // 3. 我是最后一个响应地主的人（队友已过牌）→ 尽量接过控制，按"最小破坏"选牌
        if (myLastResponder) {
            pickFollow(hand, sameTypeSafe, lastPlay)?.let { return it }
            // 无安全可跟牌：放走地主，保存结构/控牌
        }

        // 4. 普通跟牌：优先真散牌 → 其次拆对/三张 → (紧急)才拆顺子/飞机；大单张受 allowBig 限制
        pickFollow(hand, sameTypeSafe, lastPlay)?.let { return it }

        // 5. 兜底：仅紧急时才动炸弹
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

    /**
     * 队友出牌时的农民应对策略：
     *   A. 队友出大牌（mainRank >= K）→ 坚决不压，队友很可能有信心冲完
     *   B. 地主快赢（手牌 <= 4）且我能接管清完 → 用最大牌接管后连续打完
     *   C. 其他 → 用小牌顶着消耗地主，同时出掉手上零散牌（不拆大牌组）
     */
    private fun shouldInterceptTeammate(
        hand: List<Card>,
        validPlays: List<CardGroup>,
        lastPlay: CardGroup
    ): CardGroup? {
        // A. 队友出大牌：坚决不压
        if (lastPlay.mainRank >= 13) return null

        val sameType = validPlays.filter { it.type == lastPlay.type && !breaksBomb(it, hand) }
        if (sameType.isEmpty()) return null

        // B. 地主快赢且我能接管清完 → 优先用"不拆牌组"的牌接管，实在没有再退而求其次（必胜出清）
        if (ctxMinOpponentCards <= 4) {
            val takeover = sameType.filter { groupDisruption(it, hand) == 0 }.maxByOrNull { it.mainRank }
                ?: sameType.maxByOrNull { it.mainRank }
            if (takeover != null) {
                val remaining = hand.filter { c -> takeover.cards.none { it.id == c.id } }
                // 接管后能在 5 轮内清空，或剩余牌数极少（一手内走完）
                val turns = estimateHandTurns(remaining)
                if (turns <= 5 || remaining.size <= 3) return takeover
            }
        }

        // B'. 地主仅剩1张且即将出牌（在我下家）：绝不出可被接走的小牌送他获胜，
        // 直接过牌让队友的牌留在台面；能否速胜已在上面 B 处理。
        if (ctxMinOpponentCards <= 1) return null

        // C. 队友出单张/对子：坚决不拆任何牌组，只用真散牌顶；没有散牌就放过，绝不拆牌组
        if (lastPlay.type == CardType.SINGLE || lastPlay.type == CardType.PAIR) {
            val loose = sameType.filter { groupDisruption(it, hand) == 0 }
            if (loose.isNotEmpty()) return loose.minByOrNull { it.mainRank }
            return null
        }
        // 队友出其他牌型：优先不拆组的离散牌，避免为压队友拆自家牌组
        val preserving = sameType.filter { preservesGroups(it.cards, hand) }
        if (preserving.isNotEmpty()) return preserving.minByOrNull { it.mainRank }
        return sameType.minByOrNull { it.mainRank }
    }

    // ==================== 炸弹纪律 ====================

    private fun decideBomb(bombs: List<CardGroup>, hand: List<Card>): CardGroup? {
        val sorted = bombs.sortedBy { it.mainRank }
        if (sorted.isEmpty()) return null
        // 分级用炸：优先用最小的炸弹（保留大炸弹对付更大威胁），
        // 但若最小的会被 unseen 更高炸弹/火箭反制，则逐级上探，最后才用最大炸弹兜底
        val b = sorted.firstOrNull { !hasUnseenHigherBombOrRocket(it.mainRank) } ?: sorted.last()
        val canBeCountered = hasUnseenHigherBombOrRocket(b.mainRank)
        // 1. 对手距获胜很近：必须炸阻止
        if (ctxMinOpponentCards <= 2) return b
        // 2. 出炸后剩余牌可在一两手内收完：炸
        val remaining = hand.filter { c -> b.cards.none { it.id == c.id } }
        if (!canBeCountered && remaining.isNotEmpty() && canFinishRemaining(remaining)) return b
        // 3. 敌人无任何2/王且无反制炸弹：安全翻倍炸
        //    用 enemyMaxHold 只统计"敌人"的控牌（农民视角自动排除队友的2/王），避免误判有威胁而舍不得炸
        val controls = (15..17).sumOf { r -> enemyMaxHold(r) }
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
        // 非控牌优先，避免用大王兜底冲完时白白甩掉控牌（控牌最后收尾同样必胜）
        val candidates = validPlays
            .filter { it.type != CardType.BOMB && it.type != CardType.ROCKET && !breaksBombForWin(it, hand) }
            .sortedWith(compareBy({ isControlRank(it.mainRank) }, { feedDanger(it) }))
        for (c in candidates) {
            val remaining = hand.filter { card -> c.cards.none { it.id == card.id } }
            if (remaining.isEmpty()) return c
            if (isUnbeatable(c) && canWinInControl(remaining, minOf(remaining.size, 8))) return c
        }
        return null
    }

    private fun canWinInControl(hand: List<Card>, depth: Int): Boolean {
        if (hand.isEmpty()) return true
        if (depth <= 0) return false
        val plays = CardRuleEngine.findAllValidPlays(hand, null)
            .filter { it.type != CardType.BOMB && it.type != CardType.ROCKET && !breaksBombForWin(it, hand) }
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
        // 炸弹是控牌武器：能在被打断时强势夺回主动权，故不计入"正常出牌手数"（沉底保命牌）

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
        return turns.coerceAtLeast(0)
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

    /**
     * 农民视角威胁折减：unseen 同时含队友与对手牌，按对手占比折减。
     * 注意：必须用 ceil 而非 round——否则当地主仅剩1张、队友牌较多时，
     * 地主那张真实存在的牌会被算成 0 威胁，导致 isUnbeatable / canFinishGuaranteed
     * 误判「必胜」，把会被地主接走的小/中单张当安全牌打出而输掉。
     * ceil 保证：只要存在任意未见牌且地主仍有牌，威胁至少为 1（对必胜判定保持保守）。
     */
    private fun scaledThreat(count: Int): Int {
        if (ctxRole != PlayerRole.FARMER) return count
        val total = ctxMinOpponentCards + ctxTeammateCardCount
        if (total <= 0) return 0
        if (count <= 0) return 0
        return Math.ceil(count * ctxMinOpponentCards.toFloat() / total.toDouble()).toInt()
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

    /** 绝对不可被压：依据 unseen 推导当前通吃（农民视角折减队友大牌威胁） */
    private fun isUnbeatable(group: CardGroup): Boolean {
        return when (group.type) {
            CardType.SINGLE -> (group.mainRank + 1..17).none { r -> r < ctxUnseenCounts.size && unseenThreat(r) > 0 }
            CardType.PAIR -> (group.mainRank + 1..15).none { r -> r < ctxUnseenCounts.size && unseenThreat(r) >= 2 }
            CardType.STRAIGHT -> maxUnseenRun(group.mainRank, 1) < group.length
            CardType.STRAIGHT_PAIR -> maxUnseenRun(group.mainRank, 2) < group.length
            CardType.PLANE, CardType.PLANE_SINGLE, CardType.PLANE_PAIR ->
                maxUnseenRun(group.mainRank, 3) < group.length
            CardType.TRIPLE, CardType.TRIPLE_ONE, CardType.TRIPLE_TWO ->
                (group.mainRank + 1..15).none { r -> r < ctxUnseenCounts.size && unseenThreat(r) >= 3 }
            else -> false
        }
    }

    /**
     * 单个敌人最多可能握有的某点数张数上界（按对手分别推演，不偷看暗牌）。
     * 公开信息可确定：某点数总未见量为 U，单个敌人 p 能握该点数的上界 = min(U, p 剩余牌数)；
     * 取所有敌人中的最大值作为"敌人最多可能持有量"。由此：
     *  - 不会出现"两家 2+2 分家却被当成炸弹"的误报（受敌人剩余牌数硬约束）；
     *  - 农民视角只统计地主（ctxEnemyTotals 不含队友），队友大牌不再算作威胁；
     *  - 当敌人剩余牌数很少时，能正确排除其握不住三张/更高结构的情况。
     */
    private fun enemyMaxHold(r: Int): Int {
        if (r < 0 || r >= ctxUnseenCounts.size) return 0
        var maxEnemy = 0
        for (t in ctxEnemyTotals) if (t > maxEnemy) maxEnemy = t
        val u = ctxUnseenCounts[r]
        return if (u <= maxEnemy) u else maxEnemy
    }

    /** 对手视角的威胁张数：以"单个敌人最多可能持有量"上界计（农民视角自动只算地主） */
    private fun unseenThreat(r: Int): Int {
        return enemyMaxHold(r)
    }

    private fun maxUnseenRun(minRank: Int, needPerRank: Int): Int {
        var best = 0
        var cur = 0
        for (r in 3..14) {
            if (r > minRank && r < ctxUnseenCounts.size && unseenThreat(r) >= needPerRank) {
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
        // 炸弹/火箭需单个敌人握满：用各敌人剩余牌数做硬约束，避免"两家 2+2 分家"被误判成炸弹
        val anyEnemyCanHoldBomb = ctxEnemyTotals.any { it >= 4 }
        val anyEnemyCanHoldRocket = ctxEnemyTotals.any { it >= 2 }
        if (ctxUnseenCounts[16] == 1 && ctxUnseenCounts[17] == 1 && anyEnemyCanHoldRocket) return true
        if (!anyEnemyCanHoldBomb) return false
        for (r in (myBombRank + 1)..15) {
            if (ctxUnseenCounts[r] == 4) return true
        }
        return false
    }

    private fun hasUnseenBombOrRocket(): Boolean {
        if (ctxUnseenCounts.size < 18) return false
        val anyEnemyCanHoldBomb = ctxEnemyTotals.any { it >= 4 }
        val anyEnemyCanHoldRocket = ctxEnemyTotals.any { it >= 2 }
        if (ctxUnseenCounts[16] == 1 && ctxUnseenCounts[17] == 1 && anyEnemyCanHoldRocket) return true
        if (!anyEnemyCanHoldBomb) return false
        for (r in 3..15) if (ctxUnseenCounts[r] == 4) return true
        return false
    }
}
