package com.doudizhu.sim

import com.doudizhu.game.logic.MasterSearch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 大师模式仿真 harness（不进 APK，仅供 JVM 本地运行）。
 *
 * 目的：
 *  1. 标定 [MasterAIDecision] 的 LANDLORD_HUMAN_MODEL_WEIGHT（pModel）：
 *     人类农民按 [MasterSearch.humanPolicy] 走子（充当"真实人类"），
 *     扫描不同建模权重下地主的胜率，选出只赚不赔的权重。
 *  2. 回归验证：改 [MasterSearch.humanPolicy] / 搜索 / 评估后重跑，
 *     对比各权重胜率变化，防止修复引入策略退化。
 *
 * 局型：AI 地主（大师搜索 + 人类建模） vs 人类农民（humanPolicy） + AI 农民（大师搜索）。
 * 全部权重共享同一组发牌种子与座位轮换（配对采样），降低横向对比的方差。
 *
 * 用法（见 sim/run-sim.sh）：
 *   java -jar sim.jar [games] [deadlineMs] [nodeLimit] [threads] [w0,w1,...] [baseSeed] [panicBombLdSize]
 *   deadlineMs=0 进入确定性模式：纯 nodeLimit 收口、不读墙钟，同参数同种子结果完全可复现
 *   （此时建议把 nodeLimit 降到 30000~60000 控制耗时）。
 */
object SimMain {

    // —— 与 MasterAIDecision 保持一致的参数（改动生产参数时需同步）——
    private const val HUMAN_TEAMMATE_PASS_PENALTY = 30
    private const val LANDLORD_HUMAN_OPP_PENALTY = 45

    /** 单局最大步数保险（正常对局远低于此），防异常死循环 */
    private const val MAX_PLIES = 400

    /**
     * 一局对局。三家手牌位压缩，地主用大师搜索（带人类建模），
     * 人类农民用 humanPolicy，AI 农民用大师搜索（无建模）。
     * @return true = 地主胜
     */
    fun playGame(
        seed: Long,
        landlord: Int,
        humanFarmer: Int,
        modelWeight: Int,
        deadlineMs: Long,
        nodeLimit: Int,
        panicBombLdSize: Int
    ): Boolean {
        val hands = deal(seed, landlord)
        val sizes = IntArray(3) { MasterSearch.sizeOf(hands[it]) }

        var turn = landlord
        var last: MasterSearch.Move? = null
        var lastPlayer = -1
        var plies = 0

        while (plies++ < MAX_PLIES) {
            if (sizes[0] == 0 || sizes[1] == 0 || sizes[2] == 0) return sizes[landlord] == 0

            val move: MasterSearch.Move? = when {
                turn == landlord -> masterMove(
                    hands, sizes, turn, last, lastPlayer,
                    landlord, rootIsLandlord = true,
                    humanFarmer = humanFarmer, modelWeight = modelWeight,
                    deadlineMs = deadlineMs, nodeLimit = nodeLimit
                )
                turn == humanFarmer -> MasterSearch.humanPolicy(
                    hands[turn], last, lastPlayer, turn, landlord, sizes[landlord], panicBombLdSize
                )
                else -> masterMove(
                    hands, sizes, turn, last, lastPlayer,
                    landlord, rootIsLandlord = false,
                    humanFarmer = -1, modelWeight = 0,
                    deadlineMs = deadlineMs, nodeLimit = nodeLimit
                )
            }

            if (move == null) {
                // 过牌：last/lastPlayer 保持，轮到下家；若回到 lastPlayer，solve 内会归一化为自由出牌
                turn = (turn + 1) % 3
            } else {
                hands[turn] -= move.packed
                sizes[turn] -= move.realSize
                last = move
                lastPlayer = turn
                turn = (turn + 1) % 3
            }
        }
        // 异常兜底：视为地主负（保守计数，不污染胜率上界）
        return false
    }

    /** 复刻 [MasterAIDecision.decide] 的参数折算：残局衰减表 + 过牌惩罚 */
    private fun masterMove(
        hands: LongArray,
        sizes: IntArray,
        turn: Int,
        last: MasterSearch.Move?,
        lastPlayer: Int,
        landlord: Int,
        rootIsLandlord: Boolean,
        humanFarmer: Int,
        modelWeight: Int,
        deadlineMs: Long,
        nodeLimit: Int
    ): MasterSearch.Move? {
        val useModeling = rootIsLandlord && humanFarmer >= 0
        val humanHandSize = if (useModeling) sizes[humanFarmer] else 0
        // 与 MasterAIDecision 相同的残局衰减分级
        val (modelW, oppP) = when {
            !useModeling -> 0 to 0
            humanHandSize <= 6 -> 0 to 0
            humanHandSize <= 10 -> (modelWeight * 3 / 8) to (LANDLORD_HUMAN_OPP_PENALTY / 3)
            humanHandSize <= 12 -> (modelWeight * 7 / 10) to (LANDLORD_HUMAN_OPP_PENALTY * 2 / 3)
            humanHandSize <= 16 -> (modelWeight * 9 / 10) to (LANDLORD_HUMAN_OPP_PENALTY * 5 / 6)
            else -> modelWeight to LANDLORD_HUMAN_OPP_PENALTY
        }
        val passPenalty = if (rootIsLandlord) oppP else HUMAN_TEAMMATE_PASS_PENALTY

        // deadlineMs <= 0：确定性模式（纯节点数收口、不读墙钟），同参数同种子必出同一结果，供调参复现
        val deadline = if (deadlineMs <= 0) Long.MAX_VALUE else System.currentTimeMillis() + deadlineMs
        val solver = MasterSearch.Solver(
            initialHands = hands.copyOf(),
            landlord = landlord,
            rootIsLandlord = rootIsLandlord,
            deadlineMs = deadline,
            nodeLimit = nodeLimit,
            passPenalty = passPenalty,
            humanFarmer = if (useModeling) humanFarmer else -1,
            pModel = modelW,
            evalMode = 1
        )
        return solver.solve(turn, last, lastPlayer).move
    }

    /** 确定性发牌：54 张（3..15 各 4 张 + 大小王），17/17/17，底牌 3 张归地主 */
    private fun deal(seed: Long, landlord: Int): LongArray {
        val deck = ArrayList<Int>(54)
        for (r in 3..15) repeat(4) { deck.add(r) }
        deck.add(16); deck.add(17)
        deck.shuffle(Random(seed))
        val counts = Array(3) { IntArray(18) }
        for (i in 0 until 51) counts[i / 17][deck[i]]++
        for (i in 51 until 54) counts[landlord][deck[i]]++
        return LongArray(3) { MasterSearch.pack(counts[it]) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val games = args.getOrNull(0)?.toIntOrNull() ?: 240
        val deadlineMs = args.getOrNull(1)?.toLongOrNull() ?: 50L
        val nodeLimit = args.getOrNull(2)?.toIntOrNull() ?: 150_000
        val threads = args.getOrNull(3)?.toIntOrNull()
            ?: Runtime.getRuntime().availableProcessors()
        val weights = (args.getOrNull(4) ?: "0,400,600,800,1000")
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        val baseSeed = args.getOrNull(5)?.toLongOrNull() ?: 20261029L
        val panicBombLdSize = args.getOrNull(6)?.toIntOrNull() ?: 2

        println("仿真配置：games=$games/权重, deadline=${deadlineMs}ms, nodeLimit=$nodeLimit, threads=$threads, panicBombLdSize=$panicBombLdSize")
        println("权重扫描：$weights（同一组种子/座位配对采样）")
        println("局型：AI地主(大师+建模) vs 人类农民(humanPolicy) + AI农民(大师)")
        println()

        for (w in weights) {
            val wins = AtomicInteger(0)
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(threads)
            val t0 = System.currentTimeMillis()
            for (i in 0 until games) {
                pool.execute {
                    // 座位轮换：地主轮转，人类农民在两个农民座位间交替，摊平座位效应
                    val landlord = i % 3
                    val humanFarmer = (landlord + 1 + (i / 3) % 2) % 3
                    val landlordWin = playGame(
                        seed = baseSeed + i,
                        landlord = landlord,
                        humanFarmer = humanFarmer,
                        modelWeight = w,
                        deadlineMs = deadlineMs,
                        nodeLimit = nodeLimit,
                        panicBombLdSize = panicBombLdSize
                    )
                    if (landlordWin) wins.incrementAndGet()
                    val d = done.incrementAndGet()
                    if (d % 20 == 0 || d == games) {
                        println("  pModel=$w 进度 $d/$games，当前地主胜率 ${wins.get() * 1000 / d / 10.0}%")
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.HOURS)
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            val n = done.get()
            val p = wins.get().toDouble() / n
            val se = Math.sqrt(p * (1 - p) / n) * 100
            println("== pModel=$w 地主胜率 ${"%.1f".format(p * 100)}% ± ${"%.1f".format(se)}% (n=$n, ${"%.1f".format(sec)}s)")
            println()
        }
        println("说明：配对采样下各权重共享种子，横向比较差值比绝对值更可信。")
    }
}
