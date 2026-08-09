package com.doudizhu.game.logic

import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.model.PlayerRole

/**
 * 大师模式 AI 决策（骨架）
 *
 * 设计目标：
 *  - 与 [AIDecision]（普通模式）完全解耦，普通策略源码不被改动、不被影响。
 *  - 大师模式允许使用「全信息」：所有玩家手牌、出牌顺序、完整历史都可见，
 *    用于对玩家地主最大化农民胜率，或让 AI 地主打出全局最优。
 *
 * 当前骨架实现：
 *  - 三个分支（地主 / 协作农民 / 普通农民）的入口与分流已就位。
 *  - [decideFromFullInfo] 把全量真实信息折算成 [AIDecision.decide] 所需的参数并复用普通逻辑，
 *    作为占位实现（已比普通模式更强，因为喂的是真实剩余牌而非估算值）。
 *  - 真正的「农民联盟协作求解器」「全信息地主最优求解器」后续在此文件内替换占位实现即可。
 *
 * 普通农民分支（队友为人类）直接复用普通农民策略，不进入协作求解。
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
                decideLandlord(snapshot)             // TODO: 全信息地主最优求解
            snapshot.role == PlayerRole.FARMER && snapshot.teammateIsMaster ->
                decideCooperativeFarmer(snapshot)    // TODO: 农民联盟协作求解
            else ->
                decideNormalFarmer(snapshot)         // 复制普通农民策略
        }
    }

    /**
     * 大师模式叫分（当前直接复用普通模式里更激进的 MASTER 叫分阈值）
     */
    fun decideBid(hand: List<Card>, currentMaxBid: Int): Int =
        AIDecision.decideBid(hand, currentMaxBid, Difficulty.MASTER)

    // ===================== 分支占位实现（待替换为真正求解器） =====================

    private fun decideLandlord(s: Snapshot): CardGroup? = decideFromFullInfo(s)

    private fun decideCooperativeFarmer(s: Snapshot): CardGroup? = decideFromFullInfo(s)

    private fun decideNormalFarmer(s: Snapshot): CardGroup? = decideFromFullInfo(s)

    /**
     * 占位实现：用全量真实信息折算出 [AIDecision.decide] 参数并复用普通逻辑。
     * 后续由 decideLandlord / decideCooperativeFarmer 各自替换为专属求解器。
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
