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
 *  - [decideLandlord]：AI 地主。利用两家农民真实手牌做 1-ply 全信息推演，
 *    自由出牌优先领「农民压不住」的安全牌；跟牌时若另一农民也能压，则过牌坐收渔利，
 *    只有自己能压才以最小代价接管，逼近全局最优。
 *  - [decideCooperativeFarmer]：双 AI 农民联盟（队友也是大师）。基于全信息协作：
 *    队友主导本回合则直接过牌、不内斗；自由出牌优先向队友安全交棒；
 *    跟地主时若队友能用非炸弹接管则让队友出，避免两家抢着浪费牌。
 *  - [decideNormalFarmer]：队友是人类农民（或 AI 地主 + 人类农民场景下的 AI 农民）。
 *    直接复制普通模式农民策略（[AIDecision.decide] NORMAL），仅把真实全量信息喂给它，
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
        val teammateIsMaster: Boolean
    )

    /**
     * 大师模式出牌决策入口
     */
    fun decide(snapshot: Snapshot): CardGroup? {
        return when {
            snapshot.role == PlayerRole.LANDLORD ->
                decideLandlord(snapshot)             // 全信息地主最优
            snapshot.role == PlayerRole.FARMER && snapshot.teammateIsMaster ->
                decideCooperativeFarmer(snapshot)    // 农民联盟协作
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

    private fun decideLandlord(s: Snapshot): CardGroup? {
        val hand = s.hands[s.myIndex].orEmpty()
        val lastPlay = s.lastPlay
        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            s.lastPlayerIndex == s.myIndex
        return if (isFreeLead) landlordFreeLead(s, hand)
        else landlordFollow(s, hand, lastPlay!!)
    }

    /**
     * 地主自由出牌：优先领「两家农民都压不住」的安全牌，最省着清手。
     */
    private fun landlordFreeLead(s: Snapshot, hand: List<Card>): CardGroup? {
        val candidates = CardRuleEngine.findAllValidPlays(hand, null)
        if (candidates.isEmpty()) return null
        val farmerHands = s.hands.filterKeys { it != s.myIndex }.values.toList()

        fun score(g: CardGroup): Int {
            val oppCanBeat = farmerHands.any { canBeat(it, g) }
            var sc = 0
            sc += if (oppCanBeat) 0 else 1000      // 农民压不住 = 安全领出，加分
            if (isBomb(g)) sc -= 500              // 尽量不拆炸弹领出
            sc -= g.mainRank                       // 偏好领小牌试探
            sc += g.size * 2                       // 偏好一次多出牌
            if (g.type == CardType.STRAIGHT || g.type == CardType.STRAIGHT_PAIR ||
                g.type == CardType.PLANE) sc += 5  // 偏好连续牌型清手
            return sc
        }
        return candidates.maxByOrNull { score(it) }
    }

    /**
     * 地主跟牌（上一手必为某农民所出）：
     *  - 若另一家农民也能压，则过牌让两家农民内耗，地主坐收渔利；
     *  - 只有地主能压时，用最小代价接管（领出农民快赢则必压，否则尽量不浪费大牌）。
     */
    private fun landlordFollow(s: Snapshot, hand: List<Card>, lastPlay: CardGroup): CardGroup? {
        val beaters = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (beaters.isEmpty()) return null

        val normalBeats = beaters.filter { !isBomb(it) }
        val bombBeats = beaters.filter { isBomb(it) }

        val leaderFarmer = s.lastPlayerIndex
        val otherFarmer = s.hands.keys.firstOrNull { it != s.myIndex && it != leaderFarmer } ?: -1
        val otherFarmerHand = if (otherFarmer >= 0) s.hands[otherFarmer].orEmpty() else emptyList()
        val leaderFarmerCards = s.hands[leaderFarmer]?.size ?: 0

        // 另一家农民也能压制 -> 两家会内耗，地主过牌
        if (otherFarmerHand.isNotEmpty() && canBeat(otherFarmerHand, lastPlay)) return null

        // 只有地主能压
        if (normalBeats.isNotEmpty()) {
            val cheapest = normalBeats.minByOrNull { it.mainRank }!!
            val isHigh = cheapest.mainRank >= 15          // 2 / 王
            // 领出农民快赢了必压；否则若不是大牌也压，保留 2/王 等大牌
            if (!isHigh || leaderFarmerCards <= 5) return cheapest
            return null
        }
        // 只能用炸弹
        if (bombBeats.isNotEmpty()) {
            if (leaderFarmerCards <= 2) return bombBeats.minByOrNull { it.mainRank }
            return null
        }
        return null
    }

    // ===================== 协作农民（农民联盟，全信息） =====================

    private fun decideCooperativeFarmer(s: Snapshot): CardGroup? {
        val info = deriveFullInfo(s)
        val hand = s.hands[s.myIndex].orEmpty()
        val lastPlay = s.lastPlay
        val isFreeLead = lastPlay == null || lastPlay.type == CardType.INVALID ||
            s.lastPlayerIndex == s.myIndex

        // 协作第一铁律：队友正主导本回合（上一手是队友出的），直接过牌，绝不内斗
        if (!isFreeLead && s.lastPlayerIndex == info.teammateIndex) return null

        return if (isFreeLead) cooperativeFreeLead(s, hand, info)
        else cooperativeFollow(s, hand, lastPlay!!, info)
    }

    /**
     * 协作农民自由出牌：优先领「队友能接、地主接不住」的安全交棒，
     * 把控制权稳稳交到队友手上；没有则领地主也接不住的最小牌。
     */
    private fun cooperativeFreeLead(s: Snapshot, hand: List<Card>, info: FullInfo): CardGroup? {
        val candidates = CardRuleEngine.findAllValidPlays(hand, null).filter { !isBomb(it) }
        if (candidates.isEmpty()) {
            // 只剩炸弹/火箭，只能领最小的一个
            return CardRuleEngine.findAllValidPlays(hand, null).minByOrNull { it.size }
        }
        val teammateHand = s.hands[info.teammateIndex].orEmpty()
        val landlordHand = s.hands[s.landlordIndex].orEmpty()

        fun score(g: CardGroup): Int {
            val tmCan = canBeat(teammateHand, g)
            val ldCan = canBeat(landlordHand, g)
            var sc = 0
            if (tmCan && !ldCan) sc += 1000      // 队友能接、地主接不住 = 安全交棒，最优
            if (!ldCan) sc += 300               // 地主接不住也算安全
            sc -= g.mainRank                     // 领小牌试探
            sc += g.size                         // 多出牌
            return sc
        }
        return candidates.maxByOrNull { score(it) }
    }

    /**
     * 协作农民跟牌（上一手必为地主）：
     *  - 我方能用非炸弹压制时，仅当队友能用更小牌接管才让出（不对称比较，避免两家互让死锁）；
     *  - 自己接管取最小代价；地主快赢（<=2 张）才动炸弹，否则让出由队友接管。
     */
    private fun cooperativeFollow(
        s: Snapshot,
        hand: List<Card>,
        lastPlay: CardGroup,
        info: FullInfo
    ): CardGroup? {
        val teammateHand = s.hands[info.teammateIndex].orEmpty()
        val beaters = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (beaters.isEmpty()) return null

        val normalBeats = beaters.filter { !isBomb(it) }
        val bombBeats = beaters.filter { isBomb(it) }
        val landlordCards = s.hands[s.landlordIndex]?.size ?: 0

        val myMin = normalBeats.minOfOrNull { it.mainRank } ?: Int.MAX_VALUE
        // 队友用真实手牌能压制的非炸弹最小 rank（全信息，协作依据）
        val tmMin = CardRuleEngine.findAllValidPlays(teammateHand, lastPlay)
            .filter { !isBomb(it) }.minOfOrNull { it.mainRank } ?: Int.MAX_VALUE

        // 协作：我方有非炸弹压制时，仅当队友能用「更小牌」接管才让出。
        // 用严格小于（不对称）比较，避免两家都让导致地主不战而胜的死锁：
        // 出更小牌的那家出、另一家过；相等或队友更大时我方出（队友随后会因
        // 「上一手是队友」自动过牌，不会内斗）。
        if (myMin != Int.MAX_VALUE) {
            return if (tmMin < myMin) null else normalBeats.minByOrNull { it.mainRank }
        }

        // 我方只能用炸弹：地主快赢（<=2 张）才炸，否则让出（队友若有非炸弹接管会接管）
        if (bombBeats.isNotEmpty() && landlordCards <= 2) {
            return bombBeats.minByOrNull { it.mainRank }
        }
        return null
    }

    // ===================== 普通农民（复制普通模式策略，喂真实信息） =====================

    private fun decideNormalFarmer(s: Snapshot): CardGroup? = decideFromFullInfo(s)

    /**
     * 占位/普通农民实现：用全量真实信息折算出 [AIDecision.decide] 参数并复用普通逻辑。
     * 对普通农民分支这是其正式实现（复制普通策略）；地主/协作农民分支的占位也曾指向此处。
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
