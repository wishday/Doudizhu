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
 *  - 大师模式允许使用「全信息」：所有玩家手牌、出牌顺序都可见（见 [Snapshot.hands]），
 *    因此本类不再手写任何「地主/协作农民/普通农民」的分支启发式，而是把它交给
 *    [MasterSearch] 这个完美信息的两队零和搜索求解器，对根玩家所在阵营做全局最优。
 *
 * 为什么手写分支可以全部删掉：
 *  - 旧的 [decide] 分 [decideLandlord] / [decideCooperativeFarmer] / [decideNormalFarmer] 三套手写逻辑，
 *    存在多个缺陷：协作农民把「队友已 PASS」误判为「队友会接管」；跟牌一律取最小点数却会拆掉三张/炸弹；
 *    缺少手数评估、缺少座位感知、拦截阈值只看张数。这些恰恰是手写启发式难以修干净的问题。
 *  - 改用 [MasterSearch] 后，三家手牌全可见 = 完美信息博弈，两队零和，alpha-beta + 置换表 + 迭代加深
 *    直接给出「根玩家所在阵营」视角的最优解：地主局最大化地主胜、农民局最大化农民胜，
 *    且「不跟队友内斗」「残局一手制胜」「地主≤2张必拦截」等都在搜索里自然成立，不再需要特判。
 *  - 搜索只按 `rootIsLandlord`（根玩家是不是地主）这一个视角参数翻转评价，分支差异自动消失。
 *
 * 线程安全：本 object 无可变状态；[MasterSearch.Solver] 的所有可变状态都在其实例内，
 * 因此可在后台线程构造并求解（见 [GameEngine] 的 MASTER 分支），不触碰 [AIDecision] 的共享可变上下文。
 *
 * 仿真与调参（改动本文件或 [MasterSearch] 后应重跑）：
 *  - 仓库自带 JVM 仿真 harness：sim/SimMain.kt，局型为「AI地主(大师+人类建模) vs
 *    人类农民(humanPolicy 走子) + AI农民(大师)」，确定性发牌 + 配对种子，可直接对比
 *    不同 [LANDLORD_HUMAN_MODEL_WEIGHT] 下的地主胜率。
 *  - 用法：`./sim/run-sim.sh [games] [deadlineMs] [nodeLimit] [threads] [w0,w1,...] [baseSeed] [panicBombLdSize]`
 *    示例：`./sim/run-sim.sh 360 50 150000 3 0,200,400,600,800,1000`
 *  - 速度说明：单局耗时主要由 deadlineMs（每次决策的搜索预算）决定，而非 nodeLimit
 *    （中局通常打不到节点上限）；扫参时可用 20~30ms deadline 提速，再用 50ms 复核。
 *  - 确定性模式：deadlineMs=0 时纯 nodeLimit 收口、不读墙钟（见 [MasterSearch.Solver]），
 *    同参数同种子结果完全可复现，调参对比应优先用它（建议 nodeLimit 30000~60000），
 *    例如 `./sim/run-sim.sh 360 0 40000 3 0,400,800`。
 */
object MasterAIDecision {

    /**
     * 大师模式决策所需的全量快照（普通模式不会构造此对象）
     * @param myIndex 当前决策 AI 的玩家索引
     * @param role 当前 AI 的角色
     * @param landlordIndex 地主索引
     * @param hands 三家真实手牌（含人类玩家），索引为玩家 index
     * @param lastPlay 上一手出牌（自由出牌时为 null）
     * @param lastPlayerIndex 上一手出牌玩家索引（-1 表示新一轮自由出牌）
     * @param currentPlayerIndex 当前轮到谁（必等于 [myIndex]）
     * @param teammateIsMaster 队友农民是否也是大师 AI（决定过牌惩罚：队友是人类时给一点惩罚，
     *        因为搜索假设队友也最优，但人类队友未必会按最优接管）
     * @param opponentsHuman 是否存在「人类对手」：地主视角下指两家农民中至少有一个是人类（非大师）；
     *        用于地主面对人类农民时额外抢占主动权。农民视角下此字段不参与计算（见 [decide]）。
     * @param humanFarmerIndex 若地主根且对手含人类农民，则为那位人类农民的玩家索引；否则 -1。
     *        仅用于地主搜索对人类农民做对手建模（见 [decide]），农民根 / 普通模式恒为 -1。
     */
    data class Snapshot(
        val myIndex: Int,
        val role: PlayerRole,
        val landlordIndex: Int,
        val hands: Map<Int, List<Card>>,
        val lastPlay: CardGroup?,
        val lastPlayerIndex: Int,
        val currentPlayerIndex: Int,
        val teammateIsMaster: Boolean,
        val opponentsHuman: Boolean = false,
        val humanFarmerIndex: Int = -1
    )

    /**
     * 默认求解预算（主线程直接调用时的安全上限，避免卡 UI；后台线程由调用方传更大预算）
     */
    private const val DEFAULT_DEADLINE_MS = 250L
    private const val DEFAULT_NODE_LIMIT = 500_000

    /**
     * 队友是人类时的过牌惩罚：在「打与不打等价」时让 AI 更倾向于自己接管，
     * 补偿「人类队友不会按最优接管」这一搜索假设偏差。
     */
    private const val HUMAN_TEAMMATE_PASS_PENALTY = 30

    /**
     * 地主面对人类农民时的额外过牌惩罚（鼓励抢占/保留主动权，利用人类对手的次优走法）。
     * 仅当地主根且对手含人类时生效；农民局（含「2个AI都是农民」的协作）不受影响。
     */
    private const val LANDLORD_HUMAN_OPP_PENALTY = 45

    /**
     * 地主搜索对人类农民做对手建模时的「次优概率」权重（0..1000，对应 0%..100%）。
     * 仅当地主根且对手含人类时生效。以该概率假设人类农民按可预测的次优策略走子（可被设套），
     * 其余概率仍按最优 minimizer 走子（保住最坏情况下限，避免过拟合真实人类）。
     *
     * 取值 800：由 sim/SimMain 仿真标定（2026-10-29，每权重 n=360 配对种子，
     * 人类农民按 humanPolicy 走子，含门板意识/不压队友/残局开炸）。
     * 两组实验峰值位置不同（开炸阈值2@50ms：600 峰 40.0%；阈值5@35ms：1000 峰 40.3%），
     * 800 在两组中都接近峰值（39.4%~40.6%）且保留 20% 最优下限保险，跨设置最稳健。
     * 改动 humanPolicy 后必须用 sim/run-sim.sh 重新标定本常量。
     * 农民根 / 普通模式恒为 0（pModel<=0 退化为普通 min 节点），完全不受影响。
     */
    private const val LANDLORD_HUMAN_MODEL_WEIGHT = 800

    /**
     * 大师模式出牌决策入口。
     *
     * 把全量快照折算成 [MasterSearch] 的位压缩手牌 + 上一手走法，构造求解器取最优解，
     * 再把最优走法（[MasterSearch.Move]）反折算回真正的 [CardGroup]（或 null=过牌）。
     * 任何异常都回退到启发式兜底，保证永远返回合法的「出牌 / 过牌」结果。
     *
     * @param deadlineMs  绝对截止时长（毫秒），到点立刻中止回退到已搜到的最优解
     * @param nodeLimit   展开节点上限，防极端局面耗尽预算
     */
    fun decide(
        snapshot: Snapshot,
        deadlineMs: Long = DEFAULT_DEADLINE_MS,
        nodeLimit: Int = DEFAULT_NODE_LIMIT
    ): CardGroup? {
        return try {
            val hand = snapshot.hands[snapshot.myIndex].orEmpty()

            // 评价视角：根玩家是不是地主，决定以哪一队为「我方」。
            val rootIsLandlord = snapshot.role == PlayerRole.LANDLORD
            // 队友是人类（农民局且队友不是大师）时给一点点过牌惩罚。
            val teammateIsHuman = !rootIsLandlord && !snapshot.teammateIsMaster
            // 地主根 + 存在人类农民时：开启「人类农民对手建模」并叠加一点过牌惩罚，
            // 鼓励地主按人类农民可预测的次优走法主动设套、抢占主动权。
            // 两个分支都仅在 rootIsLandlord 时生效，因此「2个AI都是农民」的协作局（根=农民）
            // 与普通模式绝不会被影响。
            val useModeling = rootIsLandlord && snapshot.humanFarmerIndex >= 0
            // 残局感知：人类农民手牌越少越接近最优，建模权重与抢权惩罚随之衰减，
            // 回退到纯最优 minimizer（pModel=0 的稳健基线），避免在残局基于"人类会犯错"的假设踏空。
            // 分级（按人类农民手牌数）：≤6 强衰减 / ≤10 中衰减 / ≤12 轻衰减 / ≤16 微衰减 / >16 不衰减。
            val humanHandSize = if (useModeling) (snapshot.hands[snapshot.humanFarmerIndex]?.size ?: 0) else 0
            val (modelW, oppP) = when {
                !useModeling -> 0 to 0
                humanHandSize <= 6 -> 0 to 0
                humanHandSize <= 10 -> (LANDLORD_HUMAN_MODEL_WEIGHT * 3 / 8) to (LANDLORD_HUMAN_OPP_PENALTY / 3)
                humanHandSize <= 12 -> (LANDLORD_HUMAN_MODEL_WEIGHT * 7 / 10) to (LANDLORD_HUMAN_OPP_PENALTY * 2 / 3)
                humanHandSize <= 16 -> (LANDLORD_HUMAN_MODEL_WEIGHT * 9 / 10) to (LANDLORD_HUMAN_OPP_PENALTY * 5 / 6)
                else -> LANDLORD_HUMAN_MODEL_WEIGHT to LANDLORD_HUMAN_OPP_PENALTY
            }
            val oppPenalty = oppP
            val passPenalty = (if (teammateIsHuman) HUMAN_TEAMMATE_PASS_PENALTY else 0) + oppPenalty

            val hands = LongArray(3) { i -> packHand(snapshot.hands[i] ?: emptyList()) }
            val lastMove = cardGroupToMove(snapshot.lastPlay)

            val solver = MasterSearch.Solver(
                initialHands = hands,
                landlord = snapshot.landlordIndex,
                rootIsLandlord = rootIsLandlord,
                deadlineMs = System.currentTimeMillis() + deadlineMs,
                nodeLimit = nodeLimit,
                passPenalty = passPenalty,
                humanFarmer = if (useModeling) snapshot.humanFarmerIndex else -1,
                pModel = modelW,
                evalMode = 1
            )
            val result = solver.solve(snapshot.currentPlayerIndex, lastMove, snapshot.lastPlayerIndex)

            if (result.move != null) moveToCardGroup(result.move, hand) else null
        } catch (e: Throwable) {
            // 任何异常（不应发生）都回退到启发式，保证不会抛到引擎层导致卡死
            // 农民局且上一手是队友出的：兜底也不能压队友（能一手走完除外）
            val lastFromTeammate = snapshot.role == PlayerRole.FARMER &&
                snapshot.lastPlayerIndex >= 0 &&
                snapshot.lastPlayerIndex != snapshot.myIndex &&
                snapshot.lastPlayerIndex != snapshot.landlordIndex
            fallbackDecision(
                snapshot.hands[snapshot.myIndex].orEmpty(),
                snapshot.lastPlay,
                lastFromTeammate
            )
        }
    }

    /**
     * 大师模式叫分：使用更保守的 MASTER 阈值（门槛高于普通模式），降低叫地主积极性
     */
    fun decideBid(hand: List<Card>, currentMaxBid: Int): Int =
        AIDecision.decideBid(hand, currentMaxBid, Difficulty.MASTER)

    // ===================== 折算工具 =====================

    /** 把一手牌（[Card] 列表）压成 [MasterSearch] 的位压缩 Long */
    private fun packHand(cards: List<Card>): Long {
        val counts = IntArray(18)
        for (c in cards) if (c.rank in 3..17) counts[c.rank]++
        return MasterSearch.pack(counts)
    }

    /**
     * 把上一手 [CardGroup] 折算成 [MasterSearch.Move]（自由出牌 / 无效 = null）。
     * 用 [CardRuleEngine.identify] 同口径的 [CardGroup.type/mainRank/length] 作为牌型指纹，
     * 保证与搜索生成走法的语义完全一致。
     */
    private fun cardGroupToMove(group: CardGroup?): MasterSearch.Move? {
        if (group == null || group.type == CardType.INVALID) return null
        val counts = IntArray(18)
        for (c in group.cards) if (c.rank in 3..17) counts[c.rank]++
        return MasterSearch.buildMove(group.type, group.mainRank, group.length, counts)
    }

    /**
     * 把搜索得到的 [MasterSearch.Move] 反折算回真正的 [CardGroup]：
     * 从根玩家真实手牌里按 rank 取出对应张数，再直接套用走法自带的
     * [MasterSearch.Move.type]/[MasterSearch.Move.mainRank]/[MasterSearch.Move.length]
     * 构造 [CardGroup]。
     *
     * 注意：这里**不**走 [CardRuleEngine.identify]。因为识别引擎会贪心地扩张飞机/顺子等结构，
     * 当某一机翼牌恰好来自「玩家手中仍持有三张」的点数时（例如用三张 5 中的一张当飞机单翼，
     * 手上还剩两张 5），identify 会把这手合法牌误判成更长的飞机而返回 INVALID。
     * 而走法自身的元数据是在生成时就确定且合法的，直接采用更稳妥，也避免改动共享的识别引擎。
     */
    private fun moveToCardGroup(move: MasterSearch.Move, hand: List<Card>): CardGroup {
        val byRank = hand.groupBy { it.rank }
        val cards = ArrayList<Card>(move.size)
        for (r in 3..17) {
            val n = MasterSearch.cnt(move.packed, r)
            if (n > 0) {
                val list = byRank[r]
                    ?: throw IllegalStateException("rank $r 不在手牌中，无法还原走法")
                cards.addAll(list.take(n))
            }
        }
        return CardGroup(move.type, move.mainRank, move.length, cards)
    }

    /**
     * 启发式兜底：搜索异常或折算失败时，用 [CardRuleEngine.findAllValidPlays] 找一手合法牌。
     * 自由出牌优先一手走完、否则领最小非炸弹；跟牌优先最小非炸弹压制、否则最小炸弹、否则过牌。
     * @param lastFromTeammate 上一手是否队友所出（仅农民局可能为 true）：
     *        队友的牌不压——除非能一手走完直接获胜，否则过牌，避免兜底路径内斗。
     */
    private fun fallbackDecision(
        hand: List<Card>,
        lastPlay: CardGroup?,
        lastFromTeammate: Boolean = false
    ): CardGroup? {
        val candidates = CardRuleEngine.findAllValidPlays(hand, lastPlay)
        if (candidates.isEmpty()) return null
        val nonBombs = candidates.filter { it.type != CardType.BOMB && it.type != CardType.ROCKET }
        val bombs = candidates.filter { it.type == CardType.BOMB || it.type == CardType.ROCKET }
        return if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            // 自由出牌
            candidates.firstOrNull { it.size == hand.size }   // 能一手走完直接走
                ?: nonBombs.minByOrNull { it.mainRank }
                ?: candidates.minByOrNull { it.mainRank }
        } else if (lastFromTeammate) {
            // 队友的牌：只在一手制胜时接手，否则过牌让队友继续
            candidates.firstOrNull { it.size == hand.size }
        } else {
            // 跟牌：非炸弹优先，保留炸弹
            nonBombs.minByOrNull { it.mainRank }
                ?: bombs.minByOrNull { it.mainRank }
        }
    }
}
