package com.doudizhu.sim

import com.doudizhu.game.logic.MasterSearch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 大师模式仿真 harness（不进 APK，仅供 JVM 本地运行）。
 *
 * 用途：在「AI 地主(大师搜索+人类建模) vs 人类农民(humanPolicy) + AI 农民(大师)」局型下，
 * 用与线上一致的 pModel=800 配置，扫描【评估函数重标定旋钮】，找出能稳定抬升地主胜率的配置。
 * 不同配置共享同一组发牌种子与座位轮换（配对采样），可逐局配对做 McNemar 显著性检验。
 *
 * 用法：
 *   java -cp <classes> com.doudizhu.sim.SimMain \
 *     [games] [deadlineMs] [nodeLimit] [threads] [pModel] [baseSeed] [panicBombLdSize] \
 *     [effTurnsBeatCoef] [controlWeight] [initWEndgameSize] [bombTempoNear] [bombTempoFar]
 *
 *   deadlineMs=0 → 确定性模式（纯 nodeLimit 收口、不读墙钟），同参数同种子完全可复现。
 *   输出：每局一行 `GAME <i> <0|1>`（1=地主胜），结尾汇总胜率±SE 与 SE 行，便于配对统计。
 */
object SimMain {

    // —— 与 MasterAIDecision 保持一致的参数（改动生产参数时需同步）——
    private const val HUMAN_TEAMMATE_PASS_PENALTY = 30
    private const val LANDLORD_HUMAN_OPP_PENALTY = 45

    /** 单局最大步数保险（正常对局远低于此），防异常死循环 */
    private const val MAX_PLIES = 400

    fun playGame(
        seed: Long,
        landlord: Int,
        humanFarmer: Int,
        modelWeight: Int,
        deadlineMs: Long,
        nodeLimit: Int,
        panicBombLdSize: Int,
        effTurnsBeatCoef: Double,
        controlWeight: Int,
        initWEndgameSize: Int,
        bombTempoNear: Int,
        bombTempoFar: Int
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
                    deadlineMs = deadlineMs, nodeLimit = nodeLimit,
                    effTurnsBeatCoef = effTurnsBeatCoef, controlWeight = controlWeight,
                    initWEndgameSize = initWEndgameSize, bombTempoNear = bombTempoNear, bombTempoFar = bombTempoFar
                )
                turn == humanFarmer -> MasterSearch.humanPolicy(
                    hands[turn], last, lastPlayer, turn, landlord, sizes[landlord], panicBombLdSize
                )
                else -> masterMove(
                    hands, sizes, turn, last, lastPlayer,
                    landlord, rootIsLandlord = false,
                    humanFarmer = -1, modelWeight = 0,
                    deadlineMs = deadlineMs, nodeLimit = nodeLimit,
                    effTurnsBeatCoef = effTurnsBeatCoef, controlWeight = controlWeight,
                    initWEndgameSize = initWEndgameSize, bombTempoNear = bombTempoNear, bombTempoFar = bombTempoFar
                )
            }

            if (move == null) {
                turn = (turn + 1) % 3
            } else {
                hands[turn] -= move.packed
                sizes[turn] -= move.realSize
                last = move
                lastPlayer = turn
                turn = (turn + 1) % 3
            }
        }
        return false
    }

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
        nodeLimit: Int,
        effTurnsBeatCoef: Double,
        controlWeight: Int,
        initWEndgameSize: Int,
        bombTempoNear: Int,
        bombTempoFar: Int
    ): MasterSearch.Move? {
        val useModeling = rootIsLandlord && humanFarmer >= 0
        val humanHandSize = if (useModeling) sizes[humanFarmer] else 0
        val (modelW, oppP) = when {
            !useModeling -> 0 to 0
            humanHandSize <= 6 -> 0 to 0
            humanHandSize <= 10 -> (modelWeight * 3 / 8) to (LANDLORD_HUMAN_OPP_PENALTY / 3)
            humanHandSize <= 12 -> (modelWeight * 7 / 10) to (LANDLORD_HUMAN_OPP_PENALTY * 2 / 3)
            humanHandSize <= 16 -> (modelWeight * 9 / 10) to (LANDLORD_HUMAN_OPP_PENALTY * 5 / 6)
            else -> modelWeight to LANDLORD_HUMAN_OPP_PENALTY
        }
        val passPenalty = if (rootIsLandlord) oppP else HUMAN_TEAMMATE_PASS_PENALTY

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
            evalMode = 1,
            effTurnsBeatCoef = effTurnsBeatCoef,
            controlWeight = controlWeight,
            initWEndgameSize = initWEndgameSize,
            bombTempoNear = bombTempoNear,
            bombTempoFar = bombTempoFar
        )
        return solver.solve(turn, last, lastPlayer).move
    }

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
        val games = args.getOrNull(0)?.toIntOrNull() ?: 300
        val deadlineMs = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val nodeLimit = args.getOrNull(2)?.toIntOrNull() ?: 10_000_000
        val threads = args.getOrNull(3)?.toIntOrNull() ?: 6
        val pModel = args.getOrNull(4)?.toIntOrNull() ?: 800
        val baseSeed = args.getOrNull(5)?.toLongOrNull() ?: 20261029L
        val panicBombLdSize = args.getOrNull(6)?.toIntOrNull() ?: 2
        val effTurnsBeatCoef = args.getOrNull(7)?.toDoubleOrNull() ?: 1.0
        val controlWeight = args.getOrNull(8)?.toIntOrNull() ?: 6
        val initWEndgameSize = args.getOrNull(9)?.toIntOrNull() ?: 6
        val bombTempoNear = args.getOrNull(10)?.toIntOrNull() ?: 6
        val bombTempoFar = args.getOrNull(11)?.toIntOrNull() ?: 2

        val cfg = "games=$games, deadline=${deadlineMs}ms, nodeLimit=$nodeLimit, threads=$threads, pModel=$pModel, baseSeed=$baseSeed, panicBombLdSize=$panicBombLdSize, effTurnsBeatCoef=$effTurnsBeatCoef, controlWeight=$controlWeight, initWEndgameSize=$initWEndgameSize, bombTempoNear=$bombTempoNear, bombTempoFar=$bombTempoFar"
        println("仿真配置：$cfg")
        println("局型：AI地主(大师+建模) vs 人类农民(humanPolicy) + AI农民(大师)")
        System.out.flush()

        val wins = AtomicInteger(0)
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val t0 = System.currentTimeMillis()
        for (i in 0 until games) {
            pool.execute {
                val landlord = i % 3
                val humanFarmer = (landlord + 1 + (i / 3) % 2) % 3
                val landlordWin = playGame(
                    seed = baseSeed + i,
                    landlord = landlord,
                    humanFarmer = humanFarmer,
                    modelWeight = pModel,
                    deadlineMs = deadlineMs,
                    nodeLimit = nodeLimit,
                    panicBombLdSize = panicBombLdSize,
                    effTurnsBeatCoef = effTurnsBeatCoef,
                    controlWeight = controlWeight,
                    initWEndgameSize = initWEndgameSize,
                    bombTempoNear = bombTempoNear,
                    bombTempoFar = bombTempoFar
                )
                val w = if (landlordWin) 1 else 0
                if (landlordWin) wins.incrementAndGet()
                val d = done.incrementAndGet()
                // 逐局配对输出（供 McNemar）：同 i 跨配置共享种子，可逐局对比地主胜负
                println("GAME $i $w")
                if (d % 20 == 0 || d == games) {
                    println("  进度 $d/$games，当前地主胜率 ${"%.1f".format(wins.get() * 100.0 / d)}%")
                    System.out.flush()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.HOURS)
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        val n = done.get()
        val p = wins.get().toDouble() / n
        val se = Math.sqrt(p * (1 - p) / n) * 100
        println("== 地主胜率 ${"%.2f".format(p * 100)}% ± ${"%.2f".format(se)}% (n=$n, ${"%.1f".format(sec)}s)")
        System.out.flush()
    }
}
