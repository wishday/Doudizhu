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

    companion object {
        private const val KEY_TOTAL_SCORE = "total_score"
        private const val KEY_GAME_COUNT = "game_count"
        private const val KEY_WIN_COUNT = "win_count"
    }

    /** 累计积分 */
    private var totalScore = 0

    /** 游戏局数统计 */
    private var gameCount = 0

    /** 玩家获胜局数统计 */
    private var winCount = 0

    /** 统计持久化：使用 Activity 级 SharedPreferences，进程重启后仍能恢复 */
    private val statsPrefs by lazy { getPreferences(MODE_PRIVATE) }

    private fun loadStats() {
        totalScore = statsPrefs.getInt(KEY_TOTAL_SCORE, 0)
        gameCount = statsPrefs.getInt(KEY_GAME_COUNT, 0)
        winCount = statsPrefs.getInt(KEY_WIN_COUNT, 0)
    }

    private fun saveStats() {
        statsPrefs.edit().apply {
            putInt(KEY_TOTAL_SCORE, totalScore)
            putInt(KEY_GAME_COUNT, gameCount)
            putInt(KEY_WIN_COUNT, winCount)
            apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复历史积分与胜率
        loadStats()

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
        gameSurfaceView.setTotalScore(totalScore)
        gameSurfaceView.setStats(gameCount, winCount)

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
            gameSurfaceView.showMessage("发牌完成", 1500)
            gameSurfaceView.refresh()
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

            if (isHumanWin) {
                totalScore += roundScore
                gameSurfaceView.showMessage("恭喜获胜！本局 +$roundScore 分", 5000)
                gameSurfaceView.playWinSound()
            } else {
                totalScore -= roundScore
                gameSurfaceView.showMessage("本局失利，${winner.name}（$roleText）获胜，-$roundScore 分", 5000)
            }

            // 统计游戏局数与玩家胜率
            gameCount++
            if (isHumanWin) winCount++
            gameSurfaceView.setStats(gameCount, winCount)
            gameSurfaceView.setTotalScore(totalScore)
            gameSurfaceView.refresh()
            saveStats()
        }
    }

    override fun onPause() {
        super.onPause()
        // 兜底保存：应用退到后台时持久化积分与胜率，避免异常退出丢数据
        saveStats()
    }

    override fun onRequestRefresh() {
        runOnUiThread {
            gameSurfaceView.refresh()
        }
    }
}
