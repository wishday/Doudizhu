package com.doudizhu.game

import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.doudizhu.game.logic.GameEngine
import com.doudizhu.game.logic.GameEngineCallback
import com.doudizhu.game.model.Card
import com.doudizhu.game.model.CardGroup
import com.doudizhu.game.model.CardType
import com.doudizhu.game.model.Difficulty
import com.doudizhu.game.ui.GameSurfaceView

/**
 * 斗地主游戏主Activity
 * 使用SurfaceView进行Canvas绘制，实现完整的游戏UI
 */
class MainActivity : AppCompatActivity(), GameEngineCallback {

    /** 游戏引擎 */
    private lateinit var gameEngine: GameEngine

    /** 游戏绘制视图 */
    private lateinit var gameSurfaceView: GameSurfaceView

    /** 按模式分别统计：积分 / 局数 / 胜场（普通、大师各自独立） */
    private data class GameStats(var score: Int = 0, var games: Int = 0, var wins: Int = 0)
    private val stats = mutableMapOf(
        Difficulty.NORMAL to GameStats(),
        Difficulty.MASTER to GameStats()
    )

    /** 统计持久化：使用 Activity 级 SharedPreferences，进程重启后仍能恢复 */
    private val statsPrefs by lazy { getPreferences(MODE_PRIVATE) }

    private fun statKey(mode: Difficulty, suffix: String) = "${mode.name.lowercase()}_$suffix"

    private fun loadStats() {
        for (mode in stats.keys) {
            val st = stats[mode]!!
            st.score = statsPrefs.getInt(statKey(mode, "score"), 0)
            st.games = statsPrefs.getInt(statKey(mode, "games"), 0)
            st.wins = statsPrefs.getInt(statKey(mode, "wins"), 0)
        }
    }

    private fun saveStats() {
        statsPrefs.edit().apply {
            for ((mode, st) in stats) {
                putInt(statKey(mode, "score"), st.score)
                putInt(statKey(mode, "games"), st.games)
                putInt(statKey(mode, "wins"), st.wins)
            }
            apply()
        }
    }

    /** 大师模式 AI 策略持久化（独立文件，与普通统计无关） */
    private val strategyPrefs by lazy { getSharedPreferences("master_strategy", MODE_PRIVATE) }
    private var farmerTeammateStrategy = Difficulty.NORMAL
    private var playerHintStrategy = Difficulty.NORMAL

    private fun loadStrategy(key: String, default: Difficulty): Difficulty {
        val s = strategyPrefs.getString(key, default.name) ?: default.name
        return try { Difficulty.valueOf(s) } catch (_: IllegalArgumentException) { default }
    }

    private fun loadMasterStrategy() {
        farmerTeammateStrategy = loadStrategy("farmer_teammate_strategy", Difficulty.NORMAL)
        playerHintStrategy = loadStrategy("player_hint_strategy", Difficulty.NORMAL)
    }

    private fun saveMasterStrategy() {
        strategyPrefs.edit().apply {
            putString("farmer_teammate_strategy", farmerTeammateStrategy.name)
            putString("player_hint_strategy", playerHintStrategy.name)
            apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复历史积分与胜率
        loadStats()
        // 恢复大师模式 AI 策略设置
        loadMasterStrategy()

        // 全屏显示
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // 创建游戏视图
        gameSurfaceView = GameSurfaceView(this)

        // 初始化游戏引擎
        gameEngine = GameEngine()
        gameEngine.callback = this
        gameSurfaceView.gameEngine = gameEngine
        for ((mode, st) in stats) {
            gameSurfaceView.setModeStats(mode, st.score, st.games, st.wins)
        }
        gameSurfaceView.setDisplayMode(Difficulty.NORMAL)

        // 主界面设置：重置某模式统计数据（清零并持久化）
        gameSurfaceView.onResetStats = { mode ->
            stats[mode]?.let { it.score = 0; it.games = 0; it.wins = 0 }
            saveStats()
            gameSurfaceView.setModeStats(mode, 0, 0, 0)
            gameSurfaceView.refresh()
        }

        // 恢复大师模式 AI 策略到引擎与 UI 显示
        gameEngine.farmerTeammateStrategy = farmerTeammateStrategy
        gameEngine.playerHintStrategy = playerHintStrategy
        gameSurfaceView.setMasterStrategy(farmerTeammateStrategy, playerHintStrategy)

        // 主界面设置：大师模式 AI 策略变更（持久化保存并实时生效）
        gameSurfaceView.onMasterStrategyChange = { farmer, hint ->
            farmerTeammateStrategy = farmer
            playerHintStrategy = hint
            gameEngine.farmerTeammateStrategy = farmer
            gameEngine.playerHintStrategy = hint
            saveMasterStrategy()
            gameSurfaceView.refresh()
        }

        setContentView(gameSurfaceView)
    }

    override fun onResume() {
        super.onResume()
        // 如果游戏还没开始，启动新游戏
        if (gameEngine.stateMachine.phase == com.doudizhu.game.state.GamePhase.DEALING
            && gameEngine.players[0].handCards.isEmpty()) {
            gameEngine.startNewGame()
        }
    }

    // ===== GameEngineCallback 实现 =====

    override fun onDealingComplete() {
        runOnUiThread {
            // 启动 3 秒手牌逐张滑入动画，动画结束后进入叫分阶段
            gameSurfaceView.startHandReveal { gameEngine.startBiddingPhase() }
        }
    }

    override fun onBiddingStart(playerIndex: Int) {
        runOnUiThread {
            val name = gameEngine.players[playerIndex].name
            if (playerIndex == 0) {
                gameSurfaceView.showMessage("轮到你叫分", 2000)
            } else {
                gameSurfaceView.showMessage("$name 正在思考...", 1500)
            }
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerBid(playerIndex: Int, score: Int) {
        runOnUiThread {
            val name = gameEngine.players[playerIndex].name
            val text = if (score == 0) "$name 不叫" else "$name 叫 $score 分"
            gameSurfaceView.showMessage(text, 1500)
            gameSurfaceView.refresh()
        }
    }

    override fun onBiddingComplete(landlordIndex: Int, bottomCards: List<Card>) {
        runOnUiThread {
            val name = gameEngine.players[landlordIndex].name
            val cards = bottomCards.joinToString(" ") { it.toString() }
            gameSurfaceView.showMessage("$name 成为地主！底牌: $cards", 3000)
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerTurn(playerIndex: Int) {
        runOnUiThread {
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerPlay(playerIndex: Int, cards: List<Card>, group: CardGroup) {
        runOnUiThread {
            gameSurfaceView.clearAllPlayedCards()
            gameSurfaceView.setTablePlayedCards(playerIndex, cards)
            val name = gameEngine.players[playerIndex].name
            val cardText = cards.joinToString(" ") { it.toString() }
            gameSurfaceView.showMessage("$name: $cardText", 2000)
            gameSurfaceView.playCardSound()

            val isLastCard = gameEngine.players[playerIndex].handCards.size == 1
            val isBig = group.type == CardType.BOMB || group.type == CardType.ROCKET || cards.size >= 8
            // 大出牌：炸弹 / 火箭 / 一次出牌≥8张，加 1.5 秒长振动（与最后一张提醒互斥，避免振动互相覆盖）
            if (isBig && !isLastCard) {
                gameSurfaceView.playBigVibration()
            }
            // 任意玩家剩最后一张牌：特殊提醒音效 + 三段脉冲振动
            if (isLastCard) {
                gameSurfaceView.playLastCardAlert()
                gameSurfaceView.playLastCardVibration()
            }
            gameSurfaceView.refresh()
        }
    }

    override fun onPlayerPass(playerIndex: Int) {
        runOnUiThread {
            val name = gameEngine.players[playerIndex].name
            gameSurfaceView.showMessage("$name 不出", 1500)
            gameSurfaceView.refresh()
        }
    }

    override fun onGameOver(winnerIndex: Int, isLandlordWin: Boolean) {
        runOnUiThread {
            val winner = gameEngine.players[winnerIndex]
            val roleText = if (isLandlordWin) "地主" else "农民"
            // 玩家是地主时地主胜即赢；玩家是农民时地主败（农民胜）即赢，队友获胜同样算我方赢
            val isHumanLandlord = gameEngine.stateMachine.landlordIndex == 0
            val isHumanWin = isHumanLandlord == isLandlordWin

            // 计算本局得分
            val baseScore = gameEngine.stateMachine.currentBidScore
            val bombCount = gameEngine.stateMachine.bombCount
            val multiplier = Math.pow(2.0, bombCount.toDouble()).toInt()
            val roundScore = baseScore * multiplier

            // 按本局 AI 难度归入对应模式的统计
            val mode = gameEngine.aiDifficulty
            val st = stats[mode]!!

            if (isHumanWin) {
                st.score += roundScore
                gameSurfaceView.showMessage("恭喜获胜！本局 +$roundScore 分", 5000)
                gameSurfaceView.playWinSound()
            } else {
                st.score -= roundScore
                gameSurfaceView.showMessage("本局失利，${winner.name}（$roleText）获胜，-$roundScore 分", 5000)
            }

            // 统计该模式的游戏局数与胜场
            st.games++
            if (isHumanWin) st.wins++
            gameSurfaceView.setModeStats(mode, st.score, st.games, st.wins)
            gameSurfaceView.setDisplayMode(mode)
            gameSurfaceView.refresh()
            saveStats()
        }
    }

    override fun onPause() {
        super.onPause()
        // 兜底保存：应用退到后台时持久化积分与胜率，避免异常退出丢数据
        saveStats()
        saveMasterStrategy()
    }

    override fun onRequestRefresh() {
        runOnUiThread {
            gameSurfaceView.refresh()
        }
    }
}
