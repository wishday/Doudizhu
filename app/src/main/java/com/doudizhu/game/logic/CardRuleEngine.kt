package com.doudizhu.game.logic

import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType

/**
 * 牌型规则引擎
 * 负责识别牌型、比较大小、校验出牌合法性
 */
object CardRuleEngine {

    /**
     * 识别一组牌的牌型
     * @param cards 待识别的牌列表（已按rank排序）
     * @return 识别出的牌组信息，无效则返回INVALID
     */
    fun identify(cards: List<Card>): CardGroup {
        if (cards.isEmpty()) return CardGroup.INVALID

        val sorted = cards.sortedBy { it.rank }
        val n = sorted.size
        val rankCounts = sorted.groupBy { it.rank }.mapValues { it.value.size }
        val counts = rankCounts.values.sortedDescending()

        return when {
            // 火箭：大小王
            n == 2 && sorted[0].rank == 16 && sorted[1].rank == 17 ->
                CardGroup(CardType.ROCKET, 17, 1, sorted)

            // 单张
            n == 1 ->
                CardGroup(CardType.SINGLE, sorted[0].rank, 1, sorted)

            // 对子
            n == 2 && counts == listOf(2) ->
                CardGroup(CardType.PAIR, sorted[0].rank, 1, sorted)

            // 炸弹：四张相同
            n == 4 && counts == listOf(4) ->
                CardGroup(CardType.BOMB, sorted[0].rank, 1, sorted)

            // 三张（不带）
            n == 3 && counts == listOf(3) ->
                CardGroup(CardType.TRIPLE, sorted[1].rank, 1, sorted)

            // 三带一
            n == 4 && counts == listOf(3, 1) -> {
                val mainRank = rankCounts.entries.first { it.value == 3 }.key
                CardGroup(CardType.TRIPLE_ONE, mainRank, 1, sorted)
            }

            // 三带二
            n == 5 && counts == listOf(3, 2) -> {
                val mainRank = rankCounts.entries.first { it.value == 3 }.key
                CardGroup(CardType.TRIPLE_TWO, mainRank, 1, sorted)
            }

            // 顺子：至少5张连续单张（rank范围3~14，不含2和王）
            n >= 5 && counts.all { it == 1 } && isConsecutive(sorted) && sorted.all { it.rank <= 14 } ->
                CardGroup(CardType.STRAIGHT, sorted[0].rank, n, sorted)

            // 连对：至少3对连续对子（rank范围3~14）
            n >= 6 && n % 2 == 0 && counts.all { it == 2 } && isConsecutiveByGroup(sorted, 2)
                    && sorted.all { it.rank <= 14 } ->
                CardGroup(CardType.STRAIGHT_PAIR, sorted[0].rank, n / 2, sorted)

            // 飞机系列（含带翼）
            else -> identifyPlane(sorted, rankCounts, n)
        }
    }

    /**
     * 识别飞机牌型（含不带/带单/带双）
     */
    private fun identifyPlane(
        sorted: List<Card>,
        rankCounts: Map<Int, Int>,
        n: Int
    ): CardGroup {
        // 提取所有三张及以上的rank
        val tripleRanks = rankCounts.filter { it.value >= 3 }.keys.sorted()
        if (tripleRanks.size < 2) return CardGroup.INVALID

        // 尝试找最长的连续三张序列（rank <= 14）
        var bestStart = -1
        var bestLen = 0
        for (i in tripleRanks.indices) {
            if (tripleRanks[i] > 14) break
            var len = 1
            for (j in i + 1 until tripleRanks.size) {
                if (tripleRanks[j] == tripleRanks[j - 1] + 1 && tripleRanks[j] <= 14) {
                    len++
                } else break
            }
            if (len >= 2 && len > bestLen) {
                bestStart = i
                bestLen = len
            }
        }

        if (bestLen < 2) return CardGroup.INVALID

        val planeRanks = tripleRanks.subList(bestStart, bestStart + bestLen)
        val planeCardCount = bestLen * 3
        val remaining = n - planeCardCount

        return when (remaining) {
            0 -> {
                // 飞机不带
                val cards = sorted.filter { it.rank in planeRanks }
                CardGroup(CardType.PLANE, planeRanks[0], bestLen, cards)
            }
            bestLen -> {
                // 飞机带单翼：翼牌数量 == 飞机长度，且每张翼牌都必须是单张（不允许对子/三张混入）
                val nonPlane = sorted.filter { it.rank !in planeRanks }
                val nonPlaneCounts = nonPlane.groupBy { it.rank }.mapValues { it.value.size }
                if (nonPlaneCounts.values.all { it == 1 } && nonPlaneCounts.size == bestLen) {
                    CardGroup(CardType.PLANE_SINGLE, planeRanks[0], bestLen, sorted)
                } else {
                    CardGroup.INVALID
                }
            }
            bestLen * 2 -> {
                // 飞机带双翼：翼牌必须是 bestLen 个对子
                val nonPlane = sorted.filter { it.rank !in planeRanks }
                val nonPlaneCounts = nonPlane.groupBy { it.rank }.mapValues { it.value.size }
                if (nonPlaneCounts.size == bestLen && nonPlaneCounts.values.all { it == 2 }) {
                    CardGroup(CardType.PLANE_PAIR, planeRanks[0], bestLen, sorted)
                } else {
                    CardGroup.INVALID
                }
            }
            else -> CardGroup.INVALID
        }
    }

    /**
     * 判断排序后的牌是否连续（单张序列）
     */
    private fun isConsecutive(sorted: List<Card>): Boolean {
        for (i in 1 until sorted.size) {
            if (sorted[i].rank != sorted[i - 1].rank + 1) return false
        }
        return true
    }

    /**
     * 判断排序后的牌是否按组连续（如连对中每两张rank相同，组间rank递增）
     * @param groupSize 每组牌的数量（对子=2）
     */
    private fun isConsecutiveByGroup(sorted: List<Card>, groupSize: Int): Boolean {
        if (sorted.size % groupSize != 0) return false
        val groups = sorted.chunked(groupSize)
        for (i in 1 until groups.size) {
            if (groups[i][0].rank != groups[i - 1][0].rank + 1) return false
        }
        return true
    }

    /**
     * 比较两组牌的大小
     * 规则：火箭最大 > 炸弹 > 普通牌型
     * 同类型同长度比较mainRank
     * @return 正数表示group1大，负数表示group2大，0表示相等
     */
    fun compare(group1: CardGroup, group2: CardGroup): Int {
        // 火箭最大
        if (group1.type == CardType.ROCKET) return 1
        if (group2.type == CardType.ROCKET) return -1

        // 炸弹 > 非炸弹
        if (group1.type == CardType.BOMB && group2.type != CardType.BOMB) return 1
        if (group2.type == CardType.BOMB && group1.type != CardType.BOMB) return -1

        // 同类型同长度才能比较mainRank
        if (group1.type == group2.type && group1.length == group2.length) {
            return group1.mainRank.compareTo(group2.mainRank)
        }

        // 不同类型或不同长度，不能比较（后出的必须同类型管或炸弹/火箭）
        return 0
    }

    /**
     * 校验出牌是否合法
     * @param played 当前出的牌
     * @param lastPlay 上一手牌（null表示自由出牌）
     * @return true表示合法
     */
    fun isValidPlay(played: CardGroup, lastPlay: CardGroup?): Boolean {
        if (played.type == CardType.INVALID) return false

        // 自由出牌（上一手为空或自己是新一轮首家）
        if (lastPlay == null || lastPlay.type == CardType.INVALID) return true

        // 火箭可以管任何牌
        if (played.type == CardType.ROCKET) return true

        // 炸弹可以管非炸弹非火箭
        if (played.type == CardType.BOMB && lastPlay.type != CardType.BOMB
            && lastPlay.type != CardType.ROCKET) return true

        // 同类型同长度，比较mainRank
        if (played.type == lastPlay.type && played.length == lastPlay.length) {
            return played.mainRank > lastPlay.mainRank
        }

        // 炸弹管炸弹：同类型（都是炸弹），比较rank
        if (played.type == CardType.BOMB && lastPlay.type == CardType.BOMB) {
            return played.mainRank > lastPlay.mainRank
        }

        return false
    }

    /**
     * 从手牌中找出所有能管住上家的合法出牌组合
     * @param hand 手牌列表
     * @param lastPlay 上一手牌
     * @return 所有合法出牌组合的列表
     */
    fun findAllValidPlays(hand: List<Card>, lastPlay: CardGroup?): List<CardGroup> {
        if (hand.isEmpty()) return emptyList()

        val results = mutableListOf<CardGroup>()
        val sorted = hand.sortedBy { it.rank }
        val countMap = sorted.groupBy { it.rank }.mapValues { it.value.size }

        // 自由出牌时返回所有可能组合（太多，这里只返回基本组合）
        if (lastPlay == null || lastPlay.type == CardType.INVALID) {
            return findAllPossiblePlays(sorted, countMap)
        }

        // 需要管牌时，找同类型且更大的
        when (lastPlay.type) {
            CardType.SINGLE -> {
                // 找所有比lastPlay.mainRank大的单张
                for (card in sorted) {
                    if (card.rank > lastPlay.mainRank) {
                        results.add(CardGroup(CardType.SINGLE, card.rank, 1, listOf(card)))
                    }
                }
            }
            CardType.PAIR -> {
                // 找所有比lastPlay.mainRank大的对子
                for ((rank, count) in countMap) {
                    if (count >= 2 && rank > lastPlay.mainRank) {
                        val cards = sorted.filter { it.rank == rank }.take(2)
                        results.add(CardGroup(CardType.PAIR, rank, 1, cards))
                    }
                }
            }
            CardType.TRIPLE -> {
                for ((rank, count) in countMap) {
                    if (count >= 3 && rank > lastPlay.mainRank) {
                        val cards = sorted.filter { it.rank == rank }.take(3)
                        results.add(CardGroup(CardType.TRIPLE, rank, 1, cards))
                    }
                }
            }
            CardType.TRIPLE_ONE -> {
                for ((rank, count) in countMap) {
                    if (count >= 3 && rank > lastPlay.mainRank) {
                        val tripleCards = sorted.filter { it.rank == rank }.take(3)
                        // 找一张附带的单牌
                        val kicker = sorted.firstOrNull { it.rank != rank }
                        if (kicker != null) {
                            results.add(CardGroup(CardType.TRIPLE_ONE, rank, 1, tripleCards + kicker))
                        }
                    }
                }
            }
            CardType.TRIPLE_TWO -> {
                for ((rank, count) in countMap) {
                    if (count >= 3 && rank > lastPlay.mainRank) {
                        val tripleCards = sorted.filter { it.rank == rank }.take(3)
                        // 找一对附带的对子
                        val pairKicker = countMap.entries.firstOrNull { it.key != rank && it.value >= 2 }
                        if (pairKicker != null) {
                            val kickerCards = sorted.filter { it.rank == pairKicker.key }.take(2)
                            results.add(CardGroup(CardType.TRIPLE_TWO, rank, 1, tripleCards + kickerCards))
                        }
                    }
                }
            }
            CardType.STRAIGHT -> {
                val len = lastPlay.length
                findStraights(sorted, countMap, len, lastPlay.mainRank).forEach { results.add(it) }
            }
            CardType.STRAIGHT_PAIR -> {
                val len = lastPlay.length
                findStraightPairs(sorted, countMap, len, lastPlay.mainRank).forEach { results.add(it) }
            }
            CardType.BOMB -> {
                // 找更大的炸弹
                for ((rank, count) in countMap) {
                    if (count >= 4 && rank > lastPlay.mainRank) {
                        val cards = sorted.filter { it.rank == rank }.take(4)
                        results.add(CardGroup(CardType.BOMB, rank, 1, cards))
                    }
                }
            }
            CardType.PLANE, CardType.PLANE_SINGLE, CardType.PLANE_PAIR -> {
                findPlanes(sorted, countMap, lastPlay).forEach { results.add(it) }
            }
            else -> {}
        }

        // 始终可以加炸弹和火箭（如果lastPlay不是火箭）
        if (lastPlay.type != CardType.ROCKET) {
            // 添加所有炸弹
            for ((rank, count) in countMap) {
                if (count >= 4) {
                    val cards = sorted.filter { it.rank == rank }.take(4)
                    val bomb = CardGroup(CardType.BOMB, rank, 1, cards)
                    // 避免重复添加
                    if (lastPlay.type != CardType.BOMB || rank > lastPlay.mainRank) {
                        if (!results.any { it.type == CardType.BOMB && it.mainRank == rank }) {
                            results.add(bomb)
                        }
                    }
                }
            }
            // 添加火箭
            val hasSmallJoker = sorted.any { it.rank == 16 }
            val hasBigJoker = sorted.any { it.rank == 17 }
            if (hasSmallJoker && hasBigJoker) {
                val jokers = sorted.filter { it.rank >= 16 }
                results.add(CardGroup(CardType.ROCKET, 17, 1, jokers))
            }
        }

        return results
    }

    /**
     * 自由出牌时找出所有可能的出牌组合
     */
    private fun findAllPossiblePlays(sorted: List<Card>, countMap: Map<Int, Int>): List<CardGroup> {
        val results = mutableListOf<CardGroup>()

        // 单张
        for (rank in countMap.keys.sorted()) {
            val card = sorted.first { it.rank == rank }
            results.add(CardGroup(CardType.SINGLE, rank, 1, listOf(card)))
        }

        // 对子
        for ((rank, count) in countMap) {
            if (count >= 2) {
                val cards = sorted.filter { it.rank == rank }.take(2)
                results.add(CardGroup(CardType.PAIR, rank, 1, cards))
            }
        }

        // 三张 / 三带一 / 三带二
        for ((rank, count) in countMap) {
            if (count >= 3) {
                val triple = sorted.filter { it.rank == rank }.take(3)
                results.add(CardGroup(CardType.TRIPLE, rank, 1, triple))
                // 三带一：找一张其他牌作为附带
                val kicker = countMap.keys.firstOrNull { it != rank }
                if (kicker != null) {
                    val k = sorted.first { it.rank == kicker }
                    results.add(CardGroup(CardType.TRIPLE_ONE, rank, 1, triple + k))
                }
                // 三带二：找一对其他牌作为附带
                val pairKicker = countMap.entries.firstOrNull { it.key != rank && it.value >= 2 }
                if (pairKicker != null) {
                    val k2 = sorted.filter { it.rank == pairKicker.key }.take(2)
                    results.add(CardGroup(CardType.TRIPLE_TWO, rank, 1, triple + k2))
                }
            }
        }

        // 炸弹
        for ((rank, count) in countMap) {
            if (count >= 4) {
                val cards = sorted.filter { it.rank == rank }.take(4)
                results.add(CardGroup(CardType.BOMB, rank, 1, cards))
            }
        }

        // 火箭
        val hasSmallJoker = sorted.any { it.rank == 16 }
        val hasBigJoker = sorted.any { it.rank == 17 }
        if (hasSmallJoker && hasBigJoker) {
            results.add(CardGroup(CardType.ROCKET, 17, 1, sorted.filter { it.rank >= 16 }))
        }

        // 顺子（只生成最短5连，避免AI开局就把长顺子打光失去控制）
        findShortStraights(sorted, countMap).forEach { results.add(it) }

        // 连对（只生成最短3连对）
        findShortStraightPairs(sorted, countMap).forEach { results.add(it) }

        return results
    }

    /**
     * 查找所有长度==5 的最短顺子（自由出牌用，避免一次打光长顺子失去控牌权）
     */
    private fun findShortStraights(
        sorted: List<Card>,
        countMap: Map<Int, Int>
    ): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val ranks = countMap.keys.filter { it in 3..14 }.sorted()
        var i = 0
        while (i < ranks.size) {
            var j = i
            while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
            val runLen = j - i + 1
            if (runLen >= 5) {
                for (start in ranks[i] until (ranks[i] + runLen - 4)) {
                    val cards = (start until start + 5).map { r -> sorted.first { it.rank == r } }
                    results.add(CardGroup(CardType.STRAIGHT, start, 5, cards))
                }
            }
            i = j + 1
        }
        return results
    }

    /**
     * 查找所有长度==3 的最短连对（自由出牌用）
     */
    private fun findShortStraightPairs(
        sorted: List<Card>,
        countMap: Map<Int, Int>
    ): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val pairRanks = countMap.filter { it.value >= 2 }.keys.filter { it in 3..14 }.sorted()
        var i = 0
        while (i < pairRanks.size) {
            var j = i
            while (j + 1 < pairRanks.size && pairRanks[j + 1] == pairRanks[j] + 1) j++
            val runLen = j - i + 1
            if (runLen >= 3) {
                for (start in pairRanks[i] until (pairRanks[i] + runLen - 2)) {
                    val cards = (start until start + 3).flatMap { r -> sorted.filter { it.rank == r }.take(2) }
                    results.add(CardGroup(CardType.STRAIGHT_PAIR, start, 3, cards))
                }
            }
            i = j + 1
        }
        return results
    }

    /**
     * 查找顺子
     */
    private fun findStraights(
        sorted: List<Card>,
        countMap: Map<Int, Int>,
        length: Int,
        minRank: Int
    ): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val availableRanks = countMap.keys.filter { it in 3..14 }.sorted()

        for (startIdx in availableRanks.indices) {
            val startRank = availableRanks[startIdx]
            if (startRank <= minRank) continue
            // 检查从startRank开始是否有length个连续rank
            val seqRanks = mutableListOf<Int>()
            for (r in startRank until startRank + length) {
                if (countMap.containsKey(r)) {
                    seqRanks.add(r)
                } else break
            }
            if (seqRanks.size >= length) {
                val cards = seqRanks.take(length).map { r -> sorted.first { it.rank == r } }
                results.add(CardGroup(CardType.STRAIGHT, seqRanks[0], length, cards))
                break // 只返回第一个找到的
            }
        }
        return results
    }

    /**
     * 查找连对
     */
    private fun findStraightPairs(
        sorted: List<Card>,
        countMap: Map<Int, Int>,
        length: Int,
        minRank: Int
    ): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val pairRanks = countMap.filter { it.value >= 2 }.keys.filter { it in 3..14 }.sorted()

        for (startIdx in pairRanks.indices) {
            val startRank = pairRanks[startIdx]
            if (startRank <= minRank) continue
            val seqRanks = mutableListOf<Int>()
            for (r in startRank until startRank + length) {
                if (countMap.containsKey(r) && countMap[r]!! >= 2) {
                    seqRanks.add(r)
                } else break
            }
            if (seqRanks.size >= length) {
                val cards = seqRanks.take(length).flatMap { r -> sorted.filter { it.rank == r }.take(2) }
                results.add(CardGroup(CardType.STRAIGHT_PAIR, seqRanks[0], length, cards))
                break
            }
        }
        return results
    }

    /**
     * 查找飞机牌型
     */
    private fun findPlanes(
        sorted: List<Card>,
        countMap: Map<Int, Int>,
        lastPlay: CardGroup
    ): List<CardGroup> {
        val results = mutableListOf<CardGroup>()
        val tripleRanks = countMap.filter { it.value >= 3 }.keys.filter { it in 3..14 }.sorted()
        val planeLen = lastPlay.length

        for (startIdx in tripleRanks.indices) {
            val startRank = tripleRanks[startIdx]
            if (startRank <= lastPlay.mainRank) continue
            val seqRanks = mutableListOf<Int>()
            for (r in startRank until startRank + planeLen) {
                if (countMap.containsKey(r) && countMap[r]!! >= 3) {
                    seqRanks.add(r)
                } else break
            }
            if (seqRanks.size >= planeLen) {
                val planeCards = seqRanks.take(planeLen).flatMap { r -> sorted.filter { it.rank == r }.take(3) }
                when (lastPlay.type) {
                    CardType.PLANE -> {
                        results.add(CardGroup(CardType.PLANE, seqRanks[0], planeLen, planeCards))
                    }
                    CardType.PLANE_SINGLE -> {
                        // 需要附带单翼
                        val usedRanks = seqRanks.take(planeLen).toSet()
                        val kickers = sorted.filter { it.rank !in usedRanks }.take(planeLen)
                        if (kickers.size >= planeLen) {
                            results.add(CardGroup(CardType.PLANE_SINGLE, seqRanks[0], planeLen, planeCards + kickers))
                        }
                    }
                    CardType.PLANE_PAIR -> {
                        val usedRanks = seqRanks.take(planeLen).toSet()
                        val remaining = sorted.filter { it.rank !in usedRanks }
                        val remainCounts = remaining.groupBy { it.rank }.mapValues { it.value.size }
                        val pairKickers = remainCounts.filter { it.value >= 2 }.keys.take(planeLen)
                        if (pairKickers.size >= planeLen) {
                            val kickerCards = pairKickers.flatMap { r -> remaining.filter { it.rank == r }.take(2) }
                            results.add(CardGroup(CardType.PLANE_PAIR, seqRanks[0], planeLen, planeCards + kickerCards))
                        }
                    }
                    else -> {}
                }
                break
            }
        }
        return results
    }
}
