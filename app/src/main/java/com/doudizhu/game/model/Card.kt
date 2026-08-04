package com.doudizhu.game.model

/**
 * 扑克牌花色枚举
 * 定义四种标准花色 + 王牌特殊标识
 */
enum class Suit {
    SPADE,      // 黑桃
    HEART,      // 红心
    CLUB,       // 梅花
    DIAMOND,    // 方块
    JOKER       // 王牌（大小王共用标识）
}

/**
 * 单张扑克牌数据类
 * @param suit 花色
 * @param rank 点数（3=3, 4=4, ..., 10=10, 11=J, 12=Q, 13=K, 14=A, 15=2, 16=小王, 17=大王）
 *             rank值越大牌越大，便于比较
 * @param id   唯一标识（0~53），用于区分54张牌
 */
data class Card(
    val suit: Suit,
    val rank: Int,
    val id: Int
) : Comparable<Card> {

    /** 获取牌面显示文字 */
    val displayText: String
        get() = when {
            rank == 16 -> "小"
            rank == 17 -> "大"
            else -> when (rank) {
                11 -> "J"
                12 -> "Q"
                13 -> "K"
                14 -> "A"
                15 -> "2"
                else -> rank.toString()
            }
        }

    /** 获取花色符号 */
    val suitSymbol: String
        get() = when (suit) {
            Suit.SPADE -> "♠"
            Suit.HEART -> "♥"
            Suit.CLUB -> "♣"
            Suit.DIAMOND -> "♦"
            Suit.JOKER -> "★"
        }

    /** 是否为红色花色 */
    val isRed: Boolean
        get() = suit == Suit.HEART || suit == Suit.DIAMOND || rank >= 16

    /** 是否为王牌 */
    val isJoker: Boolean
        get() = suit == Suit.JOKER

    /** 按rank比较大小 */
    override fun compareTo(other: Card): Int = this.rank.compareTo(other.rank)

    override fun toString(): String = "$suitSymbol$displayText"
}

/**
 * 创建一副标准54张扑克牌
 */
fun createDeck(): MutableList<Card> {
    val deck = mutableListOf<Card>()
    var id = 0
    // 四种花色，每种13张（3~2，对应rank 3~15）
    for (suit in listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND)) {
        for (rank in 3..15) {
            deck.add(Card(suit, rank, id++))
        }
    }
    // 大小王
    deck.add(Card(Suit.JOKER, 16, id++))  // 小王
    deck.add(Card(Suit.JOKER, 17, id++))  // 大王
    return deck
}
