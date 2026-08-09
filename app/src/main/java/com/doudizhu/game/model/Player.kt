package com.doudizhu.game.model

/**
 * 玩家角色枚举
 */
enum class PlayerRole {
    LANDLORD,   // 地主
    FARMER      // 农民
}

/**
 * 玩家难度枚举
 */
enum class Difficulty {
    NORMAL, // 普通
    MASTER  // 大师
}

/**
 * 玩家数据类
 * @param index 玩家索引（0=底部玩家, 1=右边AI, 2=左边AI）
 * @param name 显示名称
 * @param isHuman 是否为人类玩家
 * @param difficulty AI难度（仅AI玩家有效）
 */
data class Player(
    val index: Int,
    val name: String,
    val isHuman: Boolean,
    var difficulty: Difficulty = Difficulty.NORMAL
) {
    /** 手牌列表（CopyOnWrite：绘制线程与UI线程并发读写安全） */
    val handCards: MutableList<Card> = java.util.concurrent.CopyOnWriteArrayList()

    /** 角色 */
    var role: PlayerRole = PlayerRole.FARMER

    /** 叫分（0表示不叫） */
    var bidScore: Int = 0

    /** 获取手牌数量 */
    val cardCount: Int get() = handCards.size
}
