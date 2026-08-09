package com.doudizhu.game.logic

import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.model.PlayerRole

/**
 * 大师模式 AI 决策
 *
 * 设计目标：
 *  - 与 [AIDecision]（普通模式）完全解耦，普通策略源码不被改动、不被影响。
 *  - 大师模式允许使用「全信息」：所有玩家手牌、出牌顺序、完整历史都可见，
 *    用于对玩家地主最大化农民胜率，或让 AI 地主打出全局最优。
 *
 * 实现分三个分支（由 [decide] 分流）：
 *  - [decideLandlord]：AI 地主。利用两家农民真实手牌做全信息推演：
 *    自由出牌优先领「农民压不住」的安全牌；跟牌时若另一农民也能压则过牌让内耗，
 *    否则最小代价接管；并含一步制胜与「农民≤2张即将获胜必拦截」的残局处理。
 *  - [decideCooperativeFarmer]：双 AI 农民联盟（队友也是大师）。基于全信息协作：
 *    队友主导本回合直接过牌、不内斗；自由出牌优先向队友安全交棒（只看队友非炸弹能接，避免浪费炸弹）；
 *    跟地主用不对称比较（队友更小牌才让出）避免两家互让死锁，并含一步制胜与「地主≤2张必拦截」。
 *  - [decideNormalFarmer]：队友是人类农民（或 AI 地主 + 人类农民场景下的 AI 农民）。
 *    直接复制普通模式农民策略（[AIDecision.decide] NORMAL，自带残局/必胜逻辑），仅把真实全量信息喂给它，
 *    比普通模式更知情但不改普通算法本身。
 *
 * 依赖：[CardRuleEngine.findAllValidPlays] 做合法走法枚举，配 [Snapshot.hands] 真实手牌
 * 即可对「某家持有真实手牌时能否压制某手牌」做精确判定。
 */
object MasterAIDecision {

    /**
     * 大师模式决策所需的全量快照（普通模式不会构造此对象）
     * @param myIndex 当前决策 AI 的玩家索引
     * @param role 当前 AI 的角色
     * @param landlordIndex 地主索引
     * @param hands 三家真实手牌（含人类玩家），索引为玩家 index
     * @param lastPlay 上一手出牌（自由出牌时为 null）
     * @param lastPlayerIndex 上一手出牌玩家索引
     * @param currentPlayerIndex 当前轮到谁
     * @param history 完整出牌历史：Pair(玩家索引, 出的牌)
     * @param teammateIsMaster 队友农民是否也是大师 AI（决定是否走协作求解）
     */
    data class Snapshot(
        val myIndex: Int,
        val role: PlayerRole,
        val landlordIndex: Int,
        val hands: Map<Int, List<Card>>,
        val lastPlay: CardGroup?,
        val lastPlayerIndex: Int,
        val currentPlayerIndex: Int,
        val history: List<Pair<Int, List<Card>>>,
        val teammateIsMaster: Boolean,
        /**
         * 大师玩家索引集合（全信息）。用于一步模拟对手/队友的真实决策，
         * 仅对其中成员使用模拟，人类对手仍走启发式，避免误判。
         */
        val masters: Set<Int> = emptySet()
    )

    /**
     * 大师模式出牌决策入口
     */
    fun decide(snapshot: Snapshot, allowSim: Boolean = true): CardGroup? {
        return when {
            snapshot.role == PlayerRole.LANDLORD ->
                decideLandlord(snapshot, allowSim)   // 全信息地主最优
            snapshot.role == PlayerRole.FARMER && snapshot.teammateIsMaster ->
                decideCooperativeFarmer(snapshot, allowSim)  // 农民联盟协作
            else ->
                decideNormalFarmer(snapshot)         // 复制普通农民策略
        }
    }

    /**
     * 大师模式叫分（复用普通模式里更激进的 MASTER 叫分阈值）
     */
    fun decideBid(hand: List<Card>, currentMaxBid: Int): Int =
        AIDecision.decideBid(hand, currentMaxBid, Difficulty.MASTER)

    // ===================== 地主（全信息最优） =====================

    private fun decideLandlord(s: Snapshot, allowSim: Boolean): CardGroup? {
        val hand = s.hands[s.myIndex].orEmpty()
        val lastPlay = s.lastPlay
        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            s.lastPlayerIndex == s.myIndex
        return if (isFreeLead) landlordFreeLead(s, hand)
        else landlordFollow(s, hand, lastPlay!!, allowSim)
    }

    /**
     * 地主自由出牌：优先领「两家农民都压不住」的安全普通牌，最省着清手；能一手出完直接出。
     * 关键：炸弹/火箭绝不在开局作为「安全领出」被选中（否则会第一手甩王炸），仅在制胜或别无选择时出。
     */
    private fun landlordFreeLead(s: Snapshot, hand: List<Card>): CardGroup? {
        val candidates = CardRuleEngine.findAllValidPlays(hand, null)
        if (candidates.isEmpty()) return null
        findWinningMove(candidates, hand.size)?.let { return it }   // 一步制胜

        val farmerHands = s.hands.filterKeys { it != s.myIndex }.values.toList()

        fun score(g: CardGroup): Int {
            val oppCanBeat = farmerHands.any { canBeat(it, g) }
            var sc = 0
            // 仅「普通牌」且农民压不住才给安全分；炸弹/火箭不可作为安全领出
            if (!isBomb(g) && !oppCanBeat) sc += 1000
            if (isBomb(g)) sc -= 100000            // 炸弹/火箭绝不开局领出
            sc -= g.mainRank                       // 偏好领小牌试探
            sc += g.size * 4                       // 偏好一次多出牌（清手更快）
            // 避免把三张拆成单张试探：领单张但该 rank 自己还握着 3 张以上则罚
            if (g.type == CardType.SINGLE && hand.count { it.rank == g.mainRank } >= 3) sc -= 200
            if (g.type == CardType.STRAIGHT || g.type == CardType.STRAIGHT_PAIR ||
                g.type == CardType.PLANE) sc += 5  // 偏好连续牌型清手
            return sc
        }
        return candidates.maxByOrNull { score(it) }
    }

    /**
     * 地主跟牌（上一手必为某农民所出）：
     *  - 一步制胜 / 有农民即将获胜（≤2 张）必拦截；
     *  - 否则若另一家农民「真会压」则过牌让两家内耗；双大师局里协作农民对队友领出必过，
     *    故用全信息一步模拟其真实决策，避免误判反送主动权；
     *  - 只有地主能压时，用最小代价接管（领出农民快赢则必压，否则尽量不浪费大牌，
     *    但自身已无重夺手段时必接管，不把局送掉）。
     */
    private fun landlordFollow(
        s: Snapshot,
        hand: List<Card>,
        lastPlay: CardGroup,
        allowSim: Boolean
    ): CardGroup? {
        val beaters = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (beaters.isEmpty()) return null

        val normalBeats = beaters.filter { !isBomb(it) }
        val bombBeats = beaters.filter { isBomb(it) }

        val leaderFarmer = s.lastPlayerIndex
        val otherFarmer = s.hands.keys.firstOrNull { it != s.myIndex && it != leaderFarmer } ?: -1
        val otherFarmerHand = if (otherFarmer >= 0) s.hands[otherFarmer].orEmpty() else emptyList()
        val leaderFarmerCards = s.hands[leaderFarmer]?.size ?: 0
        val farmerHands = s.hands.filterKeys { it != s.myIndex }.values

        // ① 一步制胜：能一手出完直接出（优先非炸弹）
        findWinningMove(beaters, hand.size)?.let { return it }

        // ② 有农民即将获胜（≤2 张），必须拦截，不让其跑掉
        val minFarmerCards = farmerHands.minOfOrNull { it.size } ?: Int.MAX_VALUE
        if (minFarmerCards <= 2) {
            if (normalBeats.isNotEmpty()) return normalBeats.minByOrNull { it.mainRank }
            if (bombBeats.isNotEmpty()) return bombBeats.minByOrNull { it.mainRank }
        }

        // 另一家农民「真会压」才过牌让内耗；否则地主自己接管。
        // 双大师局：协作农民对队友领出必过牌，模拟后发现其不会压，地主应转而接管。
        if (otherFarmerHand.isNotEmpty()) {
            val otherWillBeat = if (allowSim && s.masters.contains(otherFarmer)) {
                // 模拟另一家农民「接在 leader 这手之后」的真实决策（lastPlayer 仍是 leader）
                simulateDecision(s, otherFarmer, lastPlay, s.lastPlayerIndex) != null
            } else {
                canBeat(otherFarmerHand, lastPlay)
            }
            if (otherWillBeat) return null
        }

        // 只有地主能压
        if (normalBeats.isNotEmpty()) {
            val cheapest = normalBeats.minByOrNull { it.mainRank }!!
            val isHigh = cheapest.mainRank >= 15          // 2 / 王
            // 领出农民快赢了必压；否则若不是大牌也压，保留 2/王 等大牌
            if (!isHigh || leaderFarmerCards <= 5) return cheapest
            // 想保留大牌而过牌时，必须确认自己仍有重夺手段（更大单牌或炸弹），否则必接管
            val hasRetake = bombBeats.isNotEmpty() ||
                hand.any { it.rank > cheapest.mainRank && it.rank <= 17 }
            return if (hasRetake) null else cheapest
        }
        // 只能用炸弹
        if (bombBeats.isNotEmpty()) {
            if (leaderFarmerCards <= 2) return bombBeats.minByOrNull { it.mainRank }
            return null
        }
        return null
    }

    // ===================== 协作农民（农民联盟，全信息） =====================

    private fun decideCooperativeFarmer(s: Snapshot, allowSim: Boolean): CardGroup? {
        val info = deriveFullInfo(s)
        val hand = s.hands[s.myIndex].orEmpty()
        val lastPlay = s.lastPlay
        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            s.lastPlayerIndex == s.myIndex

        // 协作第一铁律：队友正主导本回合（上一手是队友出的），直接过牌，绝不内斗
        if (!isFreeLead && s.lastPlayerIndex == info.teammateIndex) return null

        return if (isFreeLead) cooperativeFreeLead(s, hand, info)
        else cooperativeFollow(s, hand, lastPlay!!, info, allowSim)
    }

    /**
     * 协作农民自由出牌：优先领「队友能接（非炸弹）、地主接不住」的安全交棒，
     * 把控制权稳稳交到队友手上；没有则领地主也接不住的最小牌；能一手出完直接出。
     */
    private fun cooperativeFreeLead(s: Snapshot, hand: List<Card>, info: FullInfo): CardGroup? {
        val all = CardRuleEngine.findAllValidPlays(hand, null)
        findWinningMove(all, hand.size)?.let { return it }   // 一步制胜

        val candidates = all.filter { !isBomb(it) }
        if (candidates.isEmpty()) {
            // 只剩炸弹/火箭，只能领最小的一个
            return all.minByOrNull { it.size }
        }
        val teammateHand = s.hands[info.teammateIndex].orEmpty()
        val landlordHand = s.hands[s.landlordIndex].orEmpty()

        fun score(g: CardGroup): Int {
            // 交棒只看队友「非炸弹」能接，避免诱使队友浪费炸弹
            val tmCanNonBomb = CardRuleEngine.findAllValidPlays(teammateHand, g).any { !isBomb(it) }
            val ldCan = canBeat(landlordHand, g)
            var sc = 0
            if (tmCanNonBomb && !ldCan) sc += 1000   // 队友能接、地主接不住 = 安全交棒，最优
            if (!ldCan) sc += 300                    // 地主接不住也算安全
            sc -= g.mainRank                          // 领小牌试探
            sc += g.size * 3                          // 偏好一次多出牌（清手更快）
            return sc
        }
        return candidates.maxByOrNull { score(it) }
    }

    /**
     * 协作农民跟牌（上一手必为地主）：
     *  - 一步制胜 / 地主即将获胜（≤2 张）必拦截，不谦让队友；
     *  - 我方能用非炸弹压制时，用全信息一步模拟队友「真实是否会出」来决定让出：
     *    队友真会压（含其一步制胜/地主≤2拦截等）则让出，否则自己接管；
     *    嵌套模拟强制启发式（不递归），并以严格小于比较兜底，避免两家互让死锁；
     *  - 自己接管取最小代价。
     */
    private fun cooperativeFollow(
        s: Snapshot,
        hand: List<Card>,
        lastPlay: CardGroup,
        info: FullInfo,
        allowSim: Boolean
    ): CardGroup? {
        val teammateHand = s.hands[info.teammateIndex].orEmpty()
        val beaters = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (beaters.isEmpty()) return null

        val normalBeats = beaters.filter { !isBomb(it) }
        val bombBeats = beaters.filter { isBomb(it) }
        val landlordCards = s.hands[s.landlordIndex]?.size ?: 0

        // ① 一步制胜：能一手出完直接出（优先非炸弹），协作也让位于制胜
        findWinningMove(beaters, hand.size)?.let { return it }

        // ② 地主即将获胜（≤2 张），必须拦截，不谦让队友
        if (landlordCards <= 2) {
            if (normalBeats.isNotEmpty()) return normalBeats.minByOrNull { it.mainRank }
            if (bombBeats.isNotEmpty()) return bombBeats.minByOrNull { it.mainRank }
        }

        val myMin = normalBeats.minOfOrNull { it.mainRank } ?: Int.MAX_VALUE
        if (myMin != Int.MAX_VALUE) {
            // 全信息一步模拟队友对这手牌的真实决策；队友真会出则让出，否则自己接管。
            val teammateWillBeat = if (allowSim && s.masters.contains(info.teammateIndex)) {
                simulateDecision(s, info.teammateIndex, lastPlay, s.landlordIndex) != null
            } else {
                // 启发式兜底：队友能以「更小非炸弹」接管才让出（严格小于，避免死锁）
                val tmMin = CardRuleEngine.findAllValidPlays(teammateHand, lastPlay)
                    .filter { !isBomb(it) }.minOfOrNull { it.mainRank } ?: Int.MAX_VALUE
                tmMin < myMin
            }
            return if (teammateWillBeat) null else normalBeats.minByOrNull { it.mainRank }
        }

        // 我方只能用炸弹：地主快赢（<=2 张）才炸（已在②处理），否则让出由队友接管
        return null
    }

    // ===================== 普通农民（复制普通模式策略，喂真实信息） =====================

    private fun decideNormalFarmer(s: Snapshot): CardGroup? = decideFromFullInfo(s)

    /**
     * 普通农民实现：用全量真实信息折算出 [AIDecision.decide] 参数并复用普通逻辑
     * （普通逻辑自带残局/必胜处理）。对普通农民分支这是其正式实现（复制普通策略）。
     */
    private fun decideFromFullInfo(s: Snapshot): CardGroup? {
        val hand = s.hands[s.myIndex].orEmpty()
        val info = deriveFullInfo(s)

        return AIDecision.decide(
            hand = hand,
            lastPlay = s.lastPlay,
            difficulty = Difficulty.NORMAL,
            role = s.role,
            teammateCardCount = info.teammateCardCount,
            lastPlayerIndex = s.lastPlayerIndex,
            myIndex = s.myIndex,
            opponentCardCounts = info.opponentCardCounts,
            landlordIndex = s.landlordIndex,
            unseenCounts = info.unseenCounts,
            perPlayerPlayed = info.perPlayerPlayed,
            primaryOpponentIndex = info.primaryOpponentIndex,
            teammateIndex = info.teammateIndex
        )
    }

    /**
     * 从全量快照推导 [AIDecision.decide] 所需的派生信息。
     * 关键：unseenCounts 用「真实剩余牌」而非估算值（其他人手牌之和）。
     */
    private fun deriveFullInfo(s: Snapshot): FullInfo {
        val hand = s.hands[s.myIndex].orEmpty()

        // 每家已打出的每 rank 张数
        val perPlayerPlayed = Array(3) { IntArray(18) }
        val playedSum = IntArray(18)
        for ((playerIndex, cards) in s.history) {
            for (c in cards) if (c.rank in 3..17) {
                perPlayerPlayed[playerIndex][c.rank]++
                playedSum[c.rank]++
            }
        }

        // 全信息 unseen：总牌数 - 自己手牌 - 全场已打出 = 其余两家手牌之和
        val unseenCounts = IntArray(18)
        for (rank in 3..17) {
            val total = if (rank == 16 || rank == 17) 1 else 4
            val mine = hand.count { it.rank == rank }
            unseenCounts[rank] = (total - mine - playedSum[rank]).coerceAtLeast(0)
        }

        val teammateIndex = if (s.role == PlayerRole.FARMER) {
            (0..2).firstOrNull { it != s.myIndex && it != s.landlordIndex } ?: -1
        } else -1

        val primaryOpponentIndex = if (s.role == PlayerRole.FARMER) {
            s.landlordIndex
        } else {
            (s.myIndex + 1) % 3
        }

        val teammateCardCount = if (teammateIndex >= 0) s.hands[teammateIndex]?.size ?: 0 else 0

        val opponentCardCounts = if (s.role == PlayerRole.LANDLORD) {
            val others = (0..2).filter { it != s.myIndex }
            intArrayOf(s.hands[others[0]]?.size ?: 0, s.hands[others[1]]?.size ?: 0)
        } else {
            intArrayOf(s.hands[s.landlordIndex]?.size ?: 0)
        }

        return FullInfo(
            unseenCounts = unseenCounts,
            opponentCardCounts = opponentCardCounts,
            perPlayerPlayed = perPlayerPlayed,
            teammateIndex = teammateIndex,
            primaryOpponentIndex = primaryOpponentIndex,
            teammateCardCount = teammateCardCount
        )
    }

    // ===================== 工具 =====================

    /** 某家持有 [hand] 时能否压制 [group]（[group] 为 null 视为自由出牌，恒可） */
    private fun canBeat(hand: List<Card>, group: CardGroup?): Boolean =
        if (group == null) true else CardRuleEngine.findAllValidPlays(hand, group).isNotEmpty()

    /** 是否为炸弹 / 火箭（高成本牌，尽量保留） */
    private fun isBomb(g: CardGroup): Boolean = g.type == CardType.BOMB || g.type == CardType.ROCKET

    /** 一步制胜：返回能一手出完（用尽手牌）的走法，优先非炸弹；没有则返回 null */
    private fun findWinningMove(candidates: List<CardGroup>, handSize: Int): CardGroup? {
        val wins = candidates.filter { it.size == handSize }
        if (wins.isEmpty()) return null
        return wins.firstOrNull { !isBomb(it) } ?: wins.first()
    }

    /** 某农民玩家的队友索引（另一名农民） */
    private fun teammateOf(index: Int, landlordIndex: Int): Int =
        (0..2).firstOrNull { it != index && it != landlordIndex } ?: -1

    /**
     * 用全信息一步模拟某玩家对 [lastPlay]（由 [lastPlayerIndex] 所出）的真实决策。
     * 内部以 allowSim=false 调用 [decide]，确保被模拟方只走启发式、不会反向再模拟我方，
     * 从而避免无限递归。人类对手（不在 masters 中）也用普通求解，结果仅供上层参考。
     */
    private fun simulateDecision(
        s: Snapshot,
        index: Int,
        lastPlay: CardGroup?,
        lastPlayerIndex: Int
    ): CardGroup? {
        val role = if (index == s.landlordIndex) PlayerRole.LANDLORD else PlayerRole.FARMER
        val sub = Snapshot(
            myIndex = index,
            role = role,
            landlordIndex = s.landlordIndex,
            hands = s.hands,
            lastPlay = lastPlay,
            lastPlayerIndex = lastPlayerIndex,
            currentPlayerIndex = index,
            history = s.history,
            teammateIsMaster = role == PlayerRole.FARMER &&
                s.masters.contains(teammateOf(index, s.landlordIndex)),
            masters = s.masters
        )
        return decide(sub, allowSim = false)
    }

    /** [deriveFullInfo] 的派生结果载体 */
    private data class FullInfo(
        val unseenCounts: IntArray,
        val opponentCardCounts: IntArray,
        val perPlayerPlayed: Array<IntArray>,
        val teammateIndex: Int,
        val primaryOpponentIndex: Int,
        val teammateCardCount: Int
    )
}
