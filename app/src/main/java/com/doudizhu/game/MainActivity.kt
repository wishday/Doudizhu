package com.doudizhu.game

import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.doudizhu.game.logic.GameEngine
import com.doudizhu.game.logic.GameEngineCallback
import com.doudizhu.game.model.Card
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

    /** 累计积分 */
    private var totalScore = 0

    /** 游戏局数统计 */
    private var gameCount = 0

    /** 玩家获胜局数统计 */
    private var winCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    override fun onPlayerPlay(playerIndex: Int, cards: List<Card>) {
        runOnUiThread {
            gameSurfaceView.clearAllPlayedCards()
            gameSurfaceView.setTablePlayedCards(playerIndex, cards)
            val name = gameEngine.players[playerIndex].name
            val cardText = cards.joinToString(" ") { it.toString() }
            gameSurfaceView.showMessage("$name: $cardText", 2000)
            gameSurfaceView.playCardSound()
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
            val isHumanWin = winnerIndex == 0

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
        }
    }

    override fun onRequestRefresh() {
        runOnUiThread {
            gameSurfaceView.refresh()
        }
    }
}
