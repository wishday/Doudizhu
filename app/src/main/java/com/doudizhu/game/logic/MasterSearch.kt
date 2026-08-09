package com.doudizhu.game.logic

import com.doudizhu.game.model.CardType

/**
 * 大师模式专用的「完整走法生成 + 完美信息搜索」求解器。
 *
 * 为什么要单独一套（不复用 [CardRuleEngine]）：
 *  - [CardRuleEngine.findAllValidPlays] 是给普通模式与 UI 用的「候选集」，刻意做了裁剪
 *    （三带一只取第一张附牌、三带二只取第一个对子、飞机翼只取最小若干、自由出牌顺子不滑窗），
 *    最优解可能根本不在候选集里，会成为搜索的天花板；
 *  - 普通模式策略必须保持逐位一致，绝不能因为大师模式的需要去改动共享代码。
 *
 * 关键设计：
 *  - **位压缩手牌**：每手牌用一个 Long 表示，rank 3..17 各占 3 bit（0..4 张）。
 *    出牌/悔牌就是一次 `hand -= move.packed` / `hand += move.packed`（各位段不会借位，
 *    因为只会走 `hand[r] >= used[r]` 的合法走法），零分配、零数组拷贝。
 *  - **完整走法生成**：所有牌型、所有长度、所有附牌组合（飞机翼做代价排序后取前若干组，
 *    但「恰好用光手牌」的组合天然唯一因而不会被裁掉，保证一手制胜永不遗漏）。
 *  - **两队零和搜索**：三家手牌全可见 ⇒ 完美信息博弈。以「根玩家所在阵营」为唯一评价视角，
 *    同阵营节点取 max、敌方节点取 min，配 alpha-beta + 置换表 + 迭代加深。
 *    残局分支因数小，会自然搜到底得到精确解；中局达不到底时用静态评估收口。
 *  - **胜负值带层数**：`WIN - ply` / `-WIN + ply`，从而「更快取胜」与「更晚落败」都被偏好，
 *    后者在必败局面下能拖长牌局、把机会留给对手失误。
 *
 * 线程安全：本 object 只有纯函数与只读常量，全部可变状态都在 [Solver] 实例内，
 * 因此可在后台线程构造 [Solver] 求解而不与主线程冲突。
 */
internal object MasterSearch {

    /** 胜负分基准值（实际返回值会减去层数，保证更快取胜得分更高） */
    const val WIN = 1_000_000

    private const val INF = Int.MAX_VALUE / 2
    private const val MAX_DEPTH = 60

    /** 飞机翼组合枚举上限：按「拆牌代价」升序取前若干组，避免大牌手时组合爆炸 */
    private const val WING_COMBO_LIMIT = 12

    /** 置换表条目上限，超过后停止写入（避免长时间搜索吃光内存） */
    private const val TT_LIMIT = 300_000

    private const val FLAG_EXACT = 0
    private const val FLAG_LOWER = 1
    private const val FLAG_UPPER = 2

    /** PASS 在走法排序里的固定权重：低于普通走法、高于炸弹 */
    private const val PASS_ORDER = 400

    // ===================== 走法表示 =====================

    /**
     * 一步走法。
     * @param type 牌型，语义与 [CardRuleEngine.identify] 完全一致
     * @param mainRank 比较用主点数（顺子/连对/飞机取最小点数）
     * @param length 顺子=张数、连对=对数、飞机=三张组数、其余=1（与 identify 对齐）
     * @param packed 各 rank 使用张数的位压缩（每 rank 3 bit）
     * @param size 牌张总数
     */
    class Move(
        val type: CardType,
        val mainRank: Int,
        val length: Int,
        val packed: Long,
        val size: Int
    ) {
        val isBomb: Boolean = type == CardType.BOMB || type == CardType.ROCKET

        /** 置换表用的牌型指纹（同一局面下「上一手是什么」会影响可走法） */
        val key: Int = (type.ordinal shl 10) or (mainRank shl 5) or length

        /** 走法排序分（每个节点生成后写入一次，避免排序时反复计算） */
        var ord: Int = 0
    }

    /** 取位压缩手牌中某 rank 的张数 */
    fun cnt(packed: Long, rank: Int): Int = ((packed ushr (rank * 3)) and 7L).toInt()

    private fun bit(rank: Int, n: Int): Long = n.toLong() shl (rank * 3)

    /** 由 rank 计数数组（下标即 rank）压缩成 Long */
    fun pack(counts: IntArray): Long {
        var p = 0L
        for (r in 3..17) if (counts[r] > 0) p = p or bit(r, counts[r])
        return p
    }

    /** 位压缩手牌的总张数 */
    fun sizeOf(packed: Long): Int {
        var s = 0
        for (r in 3..17) s += cnt(packed, r)
        return s
    }

    /** 外部（[MasterAIDecision]）把引擎的 CardGroup 折算成 Move 时使用 */
    fun buildMove(type: CardType, mainRank: Int, length: Int, counts: IntArray): Move {
        val p = pack(counts)
        return Move(type, mainRank, length, p, sizeOf(p))
    }

    // ===================== 走法生成 =====================

    /**
     * 生成 [hand] 在 [last] 之后的全部合法走法（[last] 为 null 表示自由出牌）。
     * 不含 PASS —— PASS 由搜索层按「是否自由出牌」单独加入。
     */
    fun generate(hand: Long, last: Move?): ArrayList<Move> {
        val out = ArrayList<Move>(48)
        if (last == null) {
            genSingles(hand, out, 2)
            genPairs(hand, out, 2)
            genTriples(hand, out, 2)
            genTripleOne(hand, out, 2)
            genTripleTwo(hand, out, 2)
            genStraights(hand, out, 0, 2)
            genStraightPairs(hand, out, 0, 2)
            genPlanes(hand, out, 0, 2, 0)
            genPlanes(hand, out, 0, 2, 1)
            genPlanes(hand, out, 0, 2, 2)
            genBombs(hand, out, 2)
            genRocket(hand, out)
            return out
        }
        when (last.type) {
            CardType.SINGLE -> genSingles(hand, out, last.mainRank)
            CardType.PAIR -> genPairs(hand, out, last.mainRank)
            CardType.TRIPLE -> genTriples(hand, out, last.mainRank)
            CardType.TRIPLE_ONE -> genTripleOne(hand, out, last.mainRank)
            CardType.TRIPLE_TWO -> genTripleTwo(hand, out, last.mainRank)
            CardType.STRAIGHT -> genStraights(hand, out, last.length, last.mainRank)
            CardType.STRAIGHT_PAIR -> genStraightPairs(hand, out, last.length, last.mainRank)
            CardType.PLANE -> genPlanes(hand, out, last.length, last.mainRank, 0)
            CardType.PLANE_SINGLE -> genPlanes(hand, out, last.length, last.mainRank, 1)
            CardType.PLANE_PAIR -> genPlanes(hand, out, last.length, last.mainRank, 2)
            else -> {}
        }
        // 炸弹/火箭永远可以压（火箭除外，火箭无解）
        if (last.type != CardType.ROCKET) {
            genBombs(hand, out, if (last.type == CardType.BOMB) last.mainRank else 2)
            genRocket(hand, out)
        }
        return out
    }

    private fun genSingles(hand: Long, out: MutableList<Move>, minRank: Int) {
        var r = if (minRank + 1 > 3) minRank + 1 else 3
        while (r <= 17) {
            if (cnt(hand, r) >= 1) out.add(Move(CardType.SINGLE, r, 1, bit(r, 1), 1))
            r++
        }
    }

    private fun genPairs(hand: Long, out: MutableList<Move>, minRank: Int) {
        var r = if (minRank + 1 > 3) minRank + 1 else 3
        while (r <= 15) {
            if (cnt(hand, r) >= 2) out.add(Move(CardType.PAIR, r, 1, bit(r, 2), 2))
            r++
        }
    }

    private fun genTriples(hand: Long, out: MutableList<Move>, minRank: Int) {
        var r = if (minRank + 1 > 3) minRank + 1 else 3
        while (r <= 15) {
            if (cnt(hand, r) >= 3) out.add(Move(CardType.TRIPLE, r, 1, bit(r, 3), 3))
            r++
        }
    }

    /** 三带一：附牌枚举「所有」异点单牌（含 2 与大小王），普通模式候选集只取第一张，这里补全 */
    private fun genTripleOne(hand: Long, out: MutableList<Move>, minRank: Int) {
        var t = if (minRank + 1 > 3) minRank + 1 else 3
        while (t <= 15) {
            if (cnt(hand, t) >= 3) {
                val base = bit(t, 3)
                for (k in 3..17) {
                    if (k == t) continue
                    if (cnt(hand, k) >= 1) out.add(Move(CardType.TRIPLE_ONE, t, 1, base or bit(k, 1), 4))
                }
            }
            t++
        }
    }

    /** 三带二：附牌枚举「所有」异点对子 */
    private fun genTripleTwo(hand: Long, out: MutableList<Move>, minRank: Int) {
        var t = if (minRank + 1 > 3) minRank + 1 else 3
        while (t <= 15) {
            if (cnt(hand, t) >= 3) {
                val base = bit(t, 3)
                for (k in 3..15) {
                    if (k == t) continue
                    if (cnt(hand, k) >= 2) out.add(Move(CardType.TRIPLE_TWO, t, 1, base or bit(k, 2), 5))
                }
            }
            t++
        }
    }

    /**
     * 顺子。[fixedLen] > 0 表示跟牌（长度必须相同），=0 表示自由出牌（枚举全部起点与长度，
     * 含同一段连续牌的所有子顺子 —— 普通模式候选集不做滑窗，这里补全）。
     */
    private fun genStraights(hand: Long, out: MutableList<Move>, fixedLen: Int, minRank: Int) {
        for (s in 3..14) {
            if (s <= minRank) continue
            if (cnt(hand, s) < 1) continue
            var e = s
            while (e <= 14 && cnt(hand, e) >= 1) e++
            val maxLen = e - s
            if (fixedLen > 0) {
                if (fixedLen in 5..maxLen) out.add(straight(s, fixedLen))
            } else {
                var len = 5
                while (len <= maxLen) {
                    out.add(straight(s, len))
                    len++
                }
            }
        }
    }

    private fun straight(s: Int, len: Int): Move {
        var p = 0L
        for (r in s until s + len) p = p or bit(r, 1)
        // 与 identify 对齐：顺子的 length 记录的是张数
        return Move(CardType.STRAIGHT, s, len, p, len)
    }

    private fun genStraightPairs(hand: Long, out: MutableList<Move>, fixedLen: Int, minRank: Int) {
        for (s in 3..14) {
            if (s <= minRank) continue
            if (cnt(hand, s) < 2) continue
            var e = s
            while (e <= 14 && cnt(hand, e) >= 2) e++
            val maxLen = e - s
            if (fixedLen > 0) {
                if (fixedLen in 3..maxLen) out.add(pairRun(s, fixedLen))
            } else {
                var len = 3
                while (len <= maxLen) {
                    out.add(pairRun(s, len))
                    len++
                }
            }
        }
    }

    private fun pairRun(s: Int, len: Int): Move {
        var p = 0L
        for (r in s until s + len) p = p or bit(r, 2)
        // 与 identify 对齐：连对的 length 记录的是对数
        return Move(CardType.STRAIGHT_PAIR, s, len, p, len * 2)
    }

    /**
     * 飞机。[wing] 0=不带、1=带单翼、2=带双翼。
     * 翼牌规则与 [CardRuleEngine.identify] 保持一致：必须是 len 个「互不相同且不属于飞机」的点数，
     * 单翼每点数恰好 1 张、双翼每点数恰好 2 张。单翼允许用 2/大小王（identify 认这个牌型，
     * 且「拿王当翼一手走完」在残局是真实存在的制胜手，不能漏）。
     */
    private fun genPlanes(hand: Long, out: MutableList<Move>, fixedLen: Int, minRank: Int, wing: Int) {
        val handSize = sizeOf(hand)
        for (s in 3..14) {
            if (s <= minRank) continue
            if (cnt(hand, s) < 3) continue
            var e = s
            while (e <= 14 && cnt(hand, e) >= 3) e++
            val maxLen = e - s
            if (maxLen < 2) continue
            val lo = if (fixedLen > 0) fixedLen else 2
            val hi = if (fixedLen > 0) fixedLen else maxLen
            var len = lo
            while (len <= hi) {
                if (len < 2 || len > maxLen) {
                    len++
                    continue
                }
                var base = 0L
                for (r in s until s + len) base = base or bit(r, 3)
                when (wing) {
                    0 -> out.add(Move(CardType.PLANE, s, len, base, len * 3))
                    else -> {
                        // 若「飞机+翼」恰好用光整手牌（一手制胜），则不限翼组合枚举，
                        // 保证这手制胜牌一定被生成（否则可能被 WING_COMBO_LIMIT 裁掉而漏掉胜利）。
                        // 此时候选翼点数恰好等于 len，组合唯一，不会造成组合爆炸。
                        val limit = if (len * 3 + len * wing == handSize) Int.MAX_VALUE else WING_COMBO_LIMIT
                        addPlaneWings(hand, out, s, len, base, wing, limit)
                    }
                }
                len++
            }
        }
    }

    private fun addPlaneWings(
        hand: Long,
        out: MutableList<Move>,
        s: Int,
        len: Int,
        base: Long,
        need: Int,
        limit: Int
    ) {
        val maxRank = if (need == 1) 17 else 15
        val tmp = IntArray(16)
        var n = 0
        for (r in 3..maxRank) {
            if (r >= s && r < s + len) continue
            if (cnt(hand, r) >= need) tmp[n++] = r
        }
        if (n < len) return
        val cands = IntArray(n)
        for (i in 0 until n) cands[i] = tmp[i]
        sortByWingCost(cands, hand, need)

        val type = if (need == 1) CardType.PLANE_SINGLE else CardType.PLANE_PAIR
        val total = len * 3 + len * need
        // 注意：当候选点数恰好等于 len 时组合唯一，「用光手牌」的制胜翼组合必然在其中，
        // 因此 WING_COMBO_LIMIT 的裁剪不会漏掉一手走完的走法。
        forEachCombo(cands, len, limit) { combo ->
            var p = base
            for (i in 0 until len) p = p or bit(combo[i], need)
            out.add(Move(type, s, len, p, total))
        }
    }

    /** 翼牌代价：优先用「多余的散牌 / 低点数 / 非控牌」，最忌拆炸弹 */
    private fun wingCost(r: Int, hand: Long, need: Int): Int {
        val have = cnt(hand, r)
        var c = (have - need) * 100
        if (have == 4) c += 500
        if (r >= 15) c += 60
        return c + r
    }

    private fun sortByWingCost(a: IntArray, hand: Long, need: Int) {
        for (i in 1 until a.size) {
            val v = a[i]
            val cv = wingCost(v, hand, need)
            var j = i - 1
            while (j >= 0 && wingCost(a[j], hand, need) > cv) {
                a[j + 1] = a[j]
                j--
            }
            a[j + 1] = v
        }
    }

    /** 按字典序枚举 [cands] 中长度为 [k] 的组合，最多产出 [limit] 组；[action] 收到的数组会被复用 */
    private inline fun forEachCombo(cands: IntArray, k: Int, limit: Int, action: (IntArray) -> Unit) {
        if (k <= 0 || cands.size < k) return
        val idx = IntArray(k) { it }
        val buf = IntArray(k)
        var produced = 0
        while (true) {
            for (i in 0 until k) buf[i] = cands[idx[i]]
            action(buf)
            produced++
            if (produced >= limit) return
            var i = k - 1
            while (i >= 0 && idx[i] == cands.size - k + i) i--
            if (i < 0) return
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }

    private fun genBombs(hand: Long, out: MutableList<Move>, minRank: Int) {
        var r = if (minRank + 1 > 3) minRank + 1 else 3
        while (r <= 15) {
            if (cnt(hand, r) >= 4) out.add(Move(CardType.BOMB, r, 1, bit(r, 4), 4))
            r++
        }
    }

    private fun genRocket(hand: Long, out: MutableList<Move>) {
        if (cnt(hand, 16) >= 1 && cnt(hand, 17) >= 1) {
            out.add(Move(CardType.ROCKET, 17, 1, bit(16, 1) or bit(17, 1), 2))
        }
    }

    // ===================== 搜索 =====================

    private class TTEntry(val value: Int, val depth: Int, val flag: Int)

    /** 求解结果：[move] 为 null 表示「过牌」 */
    class Result(val move: Move?, val value: Int, val depth: Int, val complete: Boolean)

    /**
     * 完美信息求解器。一次决策构造一个实例，实例内部持有全部可变状态，故天然线程隔离。
     *
     * @param initialHands 三家位压缩手牌（下标即玩家 index）
     * @param landlord 地主索引
     * @param rootIsLandlord 根玩家是否为地主（决定评价视角属于哪一阵营）
     * @param deadlineMs 绝对截止时刻（毫秒），到点立刻中止并回退到上一轮迭代结果
     * @param nodeLimit 节点上限，防止极端局面耗尽预算
     * @param passPenalty 过牌惩罚。队友是人类时给一点点惩罚：搜索假设所有人都最优，
     *        但人类队友未必会按最优接管，因此在「打与不打等价」时倾向自己接管。
     */
    class Solver(
        initialHands: LongArray,
        private val landlord: Int,
        private val rootIsLandlord: Boolean,
        private val deadlineMs: Long,
        private val nodeLimit: Int,
        private val passPenalty: Int
    ) {
        private val hands = initialHands.copyOf()
        private val sizes = IntArray(3) { sizeOf(hands[it]) }
        private val tt = HashMap<Long, TTEntry>()
        private val scratch = IntArray(18)

        private var nodes = 0
        private var aborted = false

        /** 本次求解实际展开的节点数（调试/调参用） */
        fun nodeCount(): Int = nodes

        /**
         * 求解根局面的最佳走法。
         * @param turn 轮到谁（必须是根玩家）
         * @param last 上一手（null = 自由出牌）
         * @param lastPlayer 上一手是谁出的
         */
        fun solve(turn: Int, last: Move?, lastPlayer: Int): Result {
            val freeLead = last == null || lastPlayer == turn || lastPlayer < 0 || lastPlayer > 2
            val effLast = if (freeLead) null else last
            val effLastPlayer = if (freeLead) turn else lastPlayer

            val moves = generate(hands[turn], effLast)
            val actions = ArrayList<Move?>(moves.size + 1)
            actions.addAll(moves)
            // 只有跟牌时才能过；自由出牌必须出，保证永远不会返回 null 造成引擎兜底乱出牌
            if (!freeLead) actions.add(null)
            if (actions.isEmpty()) return Result(null, 0, 0, true)

            val hand = hands[turn]
            val handSize = sizes[turn]
            for (a in actions) if (a != null) a.ord = orderScore(a, hand, handSize)
            actions.sortByDescending { it?.ord ?: PASS_ORDER }

            var bestMove: Move? = actions[0]
            var bestValue = 0
            var reached = 0
            val next = (turn + 1) % 3

            // 迭代加深：每层完整跑完才采纳结果；超时/超节点则沿用上一层的结论。
            // 残局分支因数小，几毫秒就能搜到底拿到精确胜负；中局搜不到底则由静态评估收口。
            var depth = 1
            while (depth <= MAX_DEPTH) {
                var alpha = -INF
                var iterBest: Move? = null
                var iterValue = 0
                var hasResult = false
                var failed = false

                for (a in actions) {
                    val v: Int
                    if (a == null) {
                        v = search(next, effLast, effLastPlayer, depth - 1, alpha, INF, 1) - passPenalty
                    } else {
                        apply(turn, a)
                        v = search(next, a, turn, depth - 1, alpha, INF, 1)
                        undo(turn, a)
                    }
                    if (aborted) {
                        failed = true
                        break
                    }
                    // 严格大于才替换：等值时保留启发式排序在前的走法
                    // （不拆炸弹/不拆三张、一次多走牌、留大牌），这就是搜索等价时的取舍依据
                    if (!hasResult || v > iterValue) {
                        hasResult = true
                        iterValue = v
                        iterBest = a
                        if (v > alpha) alpha = v
                    }
                }

                if (failed || !hasResult) break
                bestMove = iterBest
                bestValue = iterValue
                reached = depth

                // 胜负已被证明，再加深没有意义
                if (bestValue >= WIN - MAX_DEPTH * 2 || bestValue <= -WIN + MAX_DEPTH * 2) break

                // 上一层最佳提到最前，显著提升下一层的 alpha-beta 剪枝效率
                if (actions.size > 1) {
                    actions.remove(iterBest)
                    actions.add(0, iterBest)
                }
                depth++
            }
            return Result(bestMove, bestValue, reached, !aborted)
        }

        /**
         * 主搜索。返回值统一为「根玩家阵营」视角：同阵营节点取 max，敌方节点取 min。
         * 两队零和，因此这套 max/min 就是精确的博弈值。
         */
        private fun search(
            turnIn: Int,
            lastIn: Move?,
            lastPlayerIn: Int,
            depth: Int,
            alphaIn: Int,
            betaIn: Int,
            ply: Int
        ): Int {
            // 终局：谁先走完谁那一方赢；带 ply 使「更快赢 / 更晚输」都被偏好
            if (sizes[0] == 0) return terminal(0, ply)
            if (sizes[1] == 0) return terminal(1, ply)
            if (sizes[2] == 0) return terminal(2, ply)

            nodes++
            if (aborted) return 0
            if (nodes >= nodeLimit) {
                aborted = true
                return 0
            }
            if ((nodes and 0x1FF) == 0 && System.currentTimeMillis() >= deadlineMs) {
                aborted = true
                return 0
            }

            // 归一化「自由出牌」：上一手是自己出的（其余两家都过了）等价于无上一手，
            // 这样不同路径到达的同一局面能命中同一个置换表条目
            val freeLead = lastIn == null || lastPlayerIn == turnIn
            val last = if (freeLead) null else lastIn
            val lastPlayer = if (freeLead) turnIn else lastPlayerIn

            if (depth <= 0) return evaluate(turnIn, last, lastPlayer)

            var alpha = alphaIn
            var beta = betaIn
            val key = stateKey(turnIn, last, lastPlayer)
            val hit = tt[key]
            if (hit != null && hit.depth >= depth) {
                when (hit.flag) {
                    FLAG_EXACT -> return hit.value
                    FLAG_LOWER -> if (hit.value > alpha) alpha = hit.value
                    else -> if (hit.value < beta) beta = hit.value
                }
                if (alpha >= beta) return hit.value
            }

            val hand = hands[turnIn]
            val handSize = sizes[turnIn]
            val moves = generate(hand, last)
            val actions = ArrayList<Move?>(moves.size + 1)
            actions.addAll(moves)
            if (!freeLead) actions.add(null)
            if (actions.isEmpty()) return evaluate(turnIn, last, lastPlayer)
            for (a in actions) if (a != null) a.ord = orderScore(a, hand, handSize)
            if (actions.size > 1) actions.sortByDescending { it?.ord ?: PASS_ORDER }

            val maximizing = isRootSide(turnIn)
            val next = (turnIn + 1) % 3
            var best = if (maximizing) -INF else INF

            for (a in actions) {
                val v: Int
                if (a == null) {
                    v = search(next, last, lastPlayer, depth - 1, alpha, beta, ply + 1)
                } else {
                    apply(turnIn, a)
                    v = search(next, a, turnIn, depth - 1, alpha, beta, ply + 1)
                    undo(turnIn, a)
                }
                if (aborted) return 0
                if (maximizing) {
                    if (v > best) best = v
                    if (best > alpha) alpha = best
                } else {
                    if (v < best) best = v
                    if (best < beta) beta = best
                }
                if (alpha >= beta) break
            }

            if (tt.size < TT_LIMIT) {
                val flag = when {
                    best <= alphaIn -> FLAG_UPPER
                    best >= betaIn -> FLAG_LOWER
                    else -> FLAG_EXACT
                }
                tt[key] = TTEntry(best, depth, flag)
            }
            return best
        }

        private fun apply(p: Int, m: Move) {
            hands[p] -= m.packed
            sizes[p] -= m.size
        }

        private fun undo(p: Int, m: Move) {
            hands[p] += m.packed
            sizes[p] += m.size
        }

        private fun isRootSide(p: Int): Boolean = (p == landlord) == rootIsLandlord

        private fun terminal(p: Int, ply: Int): Int =
            if (isRootSide(p)) WIN - ply else -WIN + ply

        // ---------- 走法排序（同时是搜索等值时的取舍依据）----------

        private fun orderScore(m: Move?, hand: Long, handSize: Int): Int {
            if (m == null) return PASS_ORDER
            if (m.size == handSize) return 100_000        // 一手走完，必然最先试
            var s = 1000 + m.size * 20 - m.mainRank * 3   // 一次多走牌、优先出小牌
            if (m.isBomb) s -= 1200                       // 炸弹是控牌资源，最后考虑
            // 不要把 2 / 王 当单张直接打出（保留控牌），后置让其最后被试
            if (m.type == CardType.SINGLE && m.mainRank >= 15) s -= 200
            s -= breakPenalty(m, hand)
            return s
        }

        /**
         * 拆牌代价：这条是「跟牌只按最小点数选，结果拆掉三张甚至拆掉炸弹」的正面修复。
         * 拆炸弹/拆王炸代价最高，其次拆三张、拆对子。
         */
        private fun breakPenalty(m: Move, hand: Long): Int {
            var p = 0
            for (r in 3..17) {
                val used = cnt(m.packed, r)
                if (used == 0) continue
                val have = cnt(hand, r)
                if (have == 4 && used < 4) p += 500
                else if (have == 3 && used < 3) p += 70
                else if (have == 2 && used < 2) p += 30
            }
            if (m.type != CardType.ROCKET && cnt(hand, 16) > 0 && cnt(hand, 17) > 0 &&
                (cnt(m.packed, 16) > 0 || cnt(m.packed, 17) > 0)
            ) {
                p += 500
            }
            return p
        }

        // ---------- 静态评估 ----------

        /**
         * 深度用尽时的局面评估，统一以「地主视角」算完再按根阵营翻转。
         *
         * 核心判据是「手数竞速」：哪一方先把手牌出完哪一方获胜，所以以双方最少手数的差值定胜负；
         * 两个农民手数差距过大说明较慢的那家是累赘（更容易被卡住），对地主更有利；
         * 再叠加控牌资源（看较强那家农民与地主对比）与当前主动权。
         */
        private fun evaluate(turn: Int, last: Move?, lastPlayer: Int): Int {
            var f0 = -1
            var f1 = -1
            for (p in 0..2) {
                if (p == landlord) continue
                if (f0 < 0) f0 = p else f1 = p
            }
            val ldT = minTurns(hands[landlord])
            val a = minTurns(hands[f0])
            val b = minTurns(hands[f1])
            val minF = if (a < b) a else b
            val maxF = if (a < b) b else a

            // 竞速：地主手数更少则对地主营（正），农民更快则对地主营为负
            var v = (minF - ldT) * 20
            // 两农民手数差距大 => 较慢一家是累赘，对地主更有利
            v += (maxF - minF) * 3
            // 控牌资源：看较强的那家农民与地主对比
            v += (control(hands[landlord]) - maxOf(control(hands[f0]), control(hands[f1]))) * 6
            // 主动权：谁握着上一手（或即将自由出牌）谁占优
            val holder = if (last == null) turn else lastPlayer
            v += if (holder == landlord) 25 else -25

            return if (rootIsLandlord) v else -v
        }

        /**
         * 估算一手牌走完所需的最少手数（越小越强）。
         * 与普通模式同样采用「先结构后三带」「先三带后结构」两种拆法取较优，
         * 避免顺子抢走三张的牌导致手数被高估。
         */
        private fun minTurns(hand: Long): Int {
            val a = turnsOrdered(hand, true)
            val b = turnsOrdered(hand, false)
            return if (a < b) a else b
        }

        private fun turnsOrdered(hand: Long, triplesFirst: Boolean): Int {
            val c = scratch
            for (r in 3..17) c[r] = cnt(hand, r)
            var t = 0
            if (c[16] > 0 && c[17] > 0) {
                t++
                c[16] = 0
                c[17] = 0
            }
            for (r in 3..15) if (c[r] == 4) {
                t++
                c[r] = 0
            }
            if (triplesFirst) {
                t += consumeTriples(c)
                t += consumeRuns(c, 3, 2)
                t += consumeRuns(c, 2, 3)
                t += consumeRuns(c, 1, 5)
            } else {
                t += consumeRuns(c, 3, 2)
                t += consumeRuns(c, 2, 3)
                t += consumeRuns(c, 1, 5)
                t += consumeTriples(c)
            }
            for (r in 3..17) {
                val n = c[r]
                if (n <= 0) continue
                t += n / 2
                if (n % 2 == 1) t++
            }
            return t
        }

        /** 吃掉连续结构：[m]=每点数张数（3=飞机/2=连对/1=顺子），[minLen]=最短段长 */
        private fun consumeRuns(c: IntArray, m: Int, minLen: Int): Int {
            var plays = 0
            var r = 3
            while (r <= 14) {
                if (c[r] < m) {
                    r++
                    continue
                }
                var e = r
                while (e <= 14 && c[e] >= m) e++
                if (e - r >= minLen) {
                    for (k in r until e) c[k] -= m
                    plays++
                }
                r = e
            }
            return plays
        }

        /** 吃掉三张：每个三张带一对或一张吸收散牌 */
        private fun consumeTriples(c: IntArray): Int {
            var t = 0
            for (r in 3..15) {
                if (c[r] != 3) continue
                c[r] = 0
                var absorbed = false
                for (k in 3..15) {
                    if (k != r && c[k] >= 2) {
                        c[k] -= 2
                        absorbed = true
                        break
                    }
                }
                if (!absorbed) {
                    for (k in 3..17) {
                        if (k != r && c[k] >= 1) {
                            c[k] -= 1
                            break
                        }
                    }
                }
                t++
            }
            return t
        }

        /** 控牌资源：A/2/王/炸弹，用于衡量夺回主动权的能力 */
        private fun control(hand: Long): Int {
            var s = cnt(hand, 14)
            s += cnt(hand, 15) * 2
            s += cnt(hand, 16) * 3 + cnt(hand, 17) * 4
            if (cnt(hand, 16) > 0 && cnt(hand, 17) > 0) s += 5
            for (r in 3..15) if (cnt(hand, r) == 4) s += 8
            return s
        }

        private fun stateKey(turn: Int, last: Move?, lastPlayer: Int): Long {
            var h = mix(hands[0] xor 0x1111111111111111L)
            h = h * 1000003L + mix(hands[1] xor 0x2222222222222222L)
            h = h * 1000003L + mix(hands[2] xor 0x3333333333333333L)
            val ctx = ((last?.key ?: 0).toLong() shl 6) or
                (((lastPlayer + 1).toLong() and 3L) shl 3) or turn.toLong()
            return h * 1000003L + mix(ctx)
        }
    }

    /** splitmix64 终混合，用于把局面折算成置换表键 */
    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
