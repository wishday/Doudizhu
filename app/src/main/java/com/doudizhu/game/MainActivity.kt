package com.doudizhu.game

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
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

    /** 调试日志：可复制文本框（叠在 SurfaceView 之上，用于真机排查触摸/振动问题） */
    private lateinit var debugLogView: TextView
    private lateinit var debugScrollView: ScrollView
    private val debugLog = StringBuilder()
    private val DEBUG_LOG_MAX_LINES = 400

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

        // 调试覆盖层：FrameLayout 包裹 SurfaceView + 可复制日志文本框
        val root = FrameLayout(this)

        val surfaceLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(gameSurfaceView, surfaceLp)

        debugLogView = TextView(this).apply {
            setTextIsSelectable(true)            // 长按即可复制
            textSize = 11f
            setTextColor(Color.parseColor("#33FF66"))
            setBackgroundColor(Color.parseColor("#CC000000"))
            text = "调试日志（长按可复制）：\n"
        }
        debugScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }
        debugScrollView.addView(debugLogView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val dm = resources.displayMetrics
        val boxW = (dm.widthPixels * 0.5f).toInt()
        val boxH = (dm.heightPixels * 0.32f).toInt()
        val logLp = FrameLayout.LayoutParams(boxW, boxH).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        root.addView(debugScrollView, logLp)

        setContentView(root)

        // 将游戏内的触摸/命中日志转发到文本框
        gameSurfaceView.logListener = { appendDebugLog(it) }
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

    /** 追加一行调试日志并刷新文本框（保持在主线程更新 UI） */
    private fun appendDebugLog(line: String) {
        runOnUiThread {
            debugLog.append(line).append('\n')
            // 限制行数，避免无限增长
            var newlines = 0
            for (c in debugLog) if (c == '\n') newlines++
            while (newlines > DEBUG_LOG_MAX_LINES) {
                val idx = debugLog.indexOf("\n")
                if (idx < 0) break
                debugLog.delete(0, idx + 1)
                newlines--
            }
            debugLogView.text = debugLog.toString()
            debugScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
