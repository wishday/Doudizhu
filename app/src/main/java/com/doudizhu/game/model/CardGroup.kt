package com.doudizhu.game.model

/**
 * 牌型枚举
 * 定义斗地主中所有合法的出牌牌型
 */
enum class CardType {
    INVALID,        // 无效牌型
    SINGLE,         // 单张
    PAIR,           // 对子
    TRIPLE,         // 三张（不带）
    TRIPLE_ONE,     // 三带一
    TRIPLE_TWO,     // 三带二
    STRAIGHT,       // 顺子（至少5张连续）
    STRAIGHT_PAIR,  // 连对（至少3对连续）
    PLANE,          // 飞机不带翼
    PLANE_SINGLE,   // 飞机带单翼
    PLANE_PAIR,     // 飞机带双翼
    BOMB,           // 炸弹（四张相同）
    ROCKET          // 火箭（双王）
}

/**
 * 一组牌的描述信息
 * @param type 牌型
 * @param mainRank 主牌rank值（用于比较大小，如三带一中的三张rank）
 * @param length 主牌连续长度（顺子/连对/飞机的长度）
 * @param cards 包含的所有牌
 */
data class CardGroup(
    val type: CardType,
    val mainRank: Int,
    val length: Int = 1,
    val cards: List<Card> = emptyList()
) {
    /** 牌的总数 */
    val size: Int get() = cards.size

    companion object {
        /** 无效牌组 */
        val INVALID = CardGroup(CardType.INVALID, 0)
    }
}
