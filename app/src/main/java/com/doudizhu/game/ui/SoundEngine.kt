package com.doudizhu.game.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * 程序化音效引擎：用正弦波 + 指数衰减包络合成 PCM，经 AudioTrack (USAGE_GAME) 播放。
 *
 * 相比直接使用 ToneGenerator 的电信令音，合成音更圆润细腻（柔和起音、泛音层次、
 * 低频"厚度"），且全部在后台线程生成与写入，不阻塞绘制/触摸线程。
 *
 * ## 路径选择：探测一次就锁定
 *
 * AudioTrack 在正常设备上创建成功率接近 100%，失败几乎总有确定原因（缓冲参数不合法、
 * 音频服务不可用、track 配额耗尽）。参数类错误重试一千次也一样失败，所以这里**不做
 * 定时冷却重试**，只用三态机：[Path.UNKNOWN] → 首次播放时探测 → 成功锁 [Path.TRACK]；
 * 连续失败 [CREATE_ATTEMPTS] 次则封存为 [Path.FALLBACK]，此后不再有任何创建开销。
 *
 * 唯一的「重建」场景是 track 被系统回收（切后台后 `state` 变 `STATE_UNINITIALIZED`），
 * 那是对象失效而非探测失败，必须重建才能出声。
 *
 * ## 另外三条硬约束（旧实现违反后会导致"永久嘟嘟声"）
 *
 *  1. **失败必须留痕**：所有失败路径都打一行 Log（TAG = DouDizhu-Audio），含 state /
 *     minBuf / buffer / 异常类型。旧实现 `catch (_: Exception) {}` 全吞，线上无从定位。
 *  2. **释放前必须等在飞任务结束，且先 pause+flush 再 release**：旧实现 `shutdown()`
 *     后立刻 `release()`，音频线程可能正阻塞在 `write()` 里，两线程同时操作同一
 *     AudioTrack —— 数据竞争会让 AudioFlinger 侧 track 泄漏，累积耗尽每 uid 配额，
 *     此后所有新建都返回 STATE_UNINITIALIZED，永久嘟嘟。
 *  3. **写入用非阻塞 + 代次检查**：新音效可立刻顶掉还在播的旧音效（不排队成长尾），
 *     也不会因 `write()` 永久阻塞而卡住 `release()`。
 *
 * 降级策略：AudioTrack 不可用时退回 ToneGenerator 提示音——宁可音色差，也不要没声音。
 */
class SoundEngine {

    private companion object {
        const val TAG = "DouDizhu-Audio"
        const val SAMPLE_RATE = 44100
        /** 单声道 16bit：每帧 2 字节。缓冲大小必须按帧对齐，否则可能被系统拒收 */
        const val FRAME_BYTES = 2
        /** 创建 AudioTrack 的最大连续失败次数：达到即封存为提示音，本次运行不再尝试 */
        const val CREATE_ATTEMPTS = 3
        /** 非阻塞写入的轮询间隔，同时决定「新音效顶掉旧音效」的响应粒度 */
        const val WRITE_POLL_MS = 4L
        /** 释放时等待音频线程退出的上限 */
        const val RELEASE_WAIT_MS = 400L
        /** 单段音频写入的最长耗时（安全阀：track 卡死时不会永久占住音频线程） */
        const val MAX_WRITE_MS = 5000L
        /**
         * 音效基准音量（线性）。1.2 让全部音效比原始合成电平高约 1.6dB。
         * 单个音效可以在 [Sfx] 里覆盖它（目前只有出牌唰声下调到 0.3）。
         */
        const val DEFAULT_GAIN = 1.2
    }

    /** 播放路径。选定之后不再反复摇摆，成功就一路用到底。 */
    private enum class Path {
        /** 尚未探测：首次播放时才知道这台设备能否用合成音 */
        UNKNOWN,
        /** 已锁定合成音（track 被系统回收时重建，仍属这条路径，不重新探测） */
        TRACK,
        /** 已封存为提示音：创建连续失败 [CREATE_ATTEMPTS] 次，本次运行不再尝试 */
        FALLBACK
    }

    /**
     * 音效清单。
     *
     * @param fallbackTone 降级为 ToneGenerator 时用的提示音
     * @param fallbackDurMs 降级提示音时长
     * @param minGapMs 同一音效的最小触发间隔（0 = 不去重）。滑动选牌会逐张触发，
     *   不去重的话 13 张会排出 1.4 秒的声音尾巴，手指早就离开了。
     * @param gain 该音效的线性音量，默认 [DEFAULT_GAIN]。基准是统一的，只有明显偏响的
     *   单个音效才在这里下调，不做全局总音量——整体缩放会把已经合适的音效一起带偏。
     */
    private enum class Sfx(
        val fallbackTone: Int,
        val fallbackDurMs: Int,
        val minGapMs: Long,
        val gain: Double = DEFAULT_GAIN
    ) {
        TAP_SELECT(ToneGenerator.TONE_PROP_ACK, 40, 110),
        TAP_DESELECT(ToneGenerator.TONE_PROP_NACK, 30, 110),

        /**
         * 出牌"唰"声：0.3，相对基准 1.2 只有 0.25 倍。
         *
         * 它是全部音效里合成电平最响的一个——`lp * 2.2` 的噪声补偿把峰值顶到接近满幅
         * （比选牌点击高 13dB），几乎没有余量，听感上明显压过其它音效，所以单独压下来。
         */
        CARD_PLAY(ToneGenerator.TONE_PROP_BEEP, 80, 0, gain = 0.3),

        BOMB(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300, 0),
        WIN(ToneGenerator.TONE_CDMA_PIP, 200, 0),
        LOSE(ToneGenerator.TONE_PROP_NACK, 200, 0),
        LAST_CARD(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260, 0),
        ERROR(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150, 0),
        CONFIRM(ToneGenerator.TONE_PROP_ACK, 50, 0)
    }

    /** 保护 track / toneGenerator 的创建与销毁（音频线程创建、主线程释放） */
    private val lock = Any()

    @Volatile private var released = false

    @Volatile private var track: AudioTrack? = null

    /**
     * 播放代次：每发起一个新音效自增。音频线程在写入循环里比对它，
     * 一旦发现被更新的音效取代就立刻退出，实现「后来者优先」。
     *
     * 用 AtomicInteger 而非 `@Volatile var` + `++`：音效既可能由 UI 线程触发（触摸），
     * 也可能由引擎回调线程触发（出牌）。`++` 是「读-改-写」，两个线程并发时会拿到
     * 同一个代次，导致「后来者优先」失效、两个音效串着播。
     */
    private val generation = AtomicInteger(0)

    /** 当前播放路径。音频线程写、其它线程读，故 volatile */
    @Volatile private var path = Path.UNKNOWN

    /** 连续创建失败次数，成功即清零（仅在 lock 内访问） */
    private var createFailures = 0

    private var toneGenerator: ToneGenerator? = null

    /** ToneGenerator 创建失败后置位：不再重复尝试，避免每一声都撞一次失败（仅 lock 内访问） */
    private var toneFailed = false

    /** PCM 缓存：合成一次反复用（仅音频线程访问，无需加锁） */
    private val pcmCache = HashMap<Sfx, ShortArray>()

    /** 每种音效上次触发时刻（用于去重，调用线程访问） */
    private val lastPlayMs = LongArray(Sfx.values().size)

    /** 串行执行音频任务的单线程调度器（避免并发写 AudioTrack） */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoundEngine").apply { isDaemon = true }
    }

    init {
        // 预热最常见的两个音效，避免第一次出牌时才在音频线程上做合成。
        // 这里**不**创建 AudioTrack：推迟到首次播放，避免在 View 构造（主线程、
        // 进程刚起）时跟系统抢音频服务，那时恰恰是最容易失败的时机。
        submit {
            pcmFor(Sfx.CARD_PLAY)
            pcmFor(Sfx.TAP_SELECT)
        }
    }

    // ==================== 对外音效接口（均异步，调用线程零开销） ====================

    /** 轻触选牌：两声上行短"嗒"音，干脆清晰 */
    fun tapSelect() = play(Sfx.TAP_SELECT)

    /** 取消选牌：一声下行短音，与选中形成方向对比 */
    fun tapDeselect() = play(Sfx.TAP_DESELECT)

    /** 出牌：模拟纸牌甩上桌面的"唰"声（噪声滑音 + 低频闷响） */
    fun cardPlay() = play(Sfx.CARD_PLAY)

    /** 炸弹/火箭：低频轰鸣 + 渐弱噪声爆炸 */
    fun bomb() = play(Sfx.BOMB)

    /** 胜利：上行琶音 C-E-G-C + 长尾和音，明亮庆祝感 */
    fun win() = play(Sfx.WIN)

    /** 失败：下行三连音，柔和不刺耳（长辈友好） */
    fun lose() = play(Sfx.LOSE)

    /** 剩最后一张提醒：双音急促提示 */
    fun lastCardAlert() = play(Sfx.LAST_CARD)

    /** 错误：低沉双"咚"，避免尖锐蜂鸣 */
    fun error() = play(Sfx.ERROR)

    /** 确认：清脆的单击"叮" */
    fun confirm() = play(Sfx.CONFIRM)

    // ==================== 播放链路 ====================

    private fun play(key: Sfx) {
        if (released) return
        val now = SystemClock.uptimeMillis()
        if (key.minGapMs > 0 && now - lastPlayMs[key.ordinal] < key.minGapMs) return
        lastPlayMs[key.ordinal] = now
        val gen = generation.incrementAndGet()
        submit { playNow(key, gen) }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // 已 release，正常运行期不会走到
        }
    }

    /** 音频线程：已封存为提示音就直接走提示音，否则先试合成路径 */
    private fun playNow(key: Sfx, gen: Int) {
        if (released) return
        if (path != Path.FALLBACK) {
            val t = obtainTrack()
            if (t != null) {
                if (writeAll(t, pcmFor(key), gen)) return
                // 写入失败：track 多半已死，丢弃后由下一次播放重建。本声改用提示音，
                // 保证「这一手出牌一定有声音」而不是静默。
                discardTrack("写入失败：${key.name}")
            }
        }
        playFallback(key)
    }

    /**
     * 取一个可用的 AudioTrack：有就复用，被系统回收就重建，从未建成过才探测。
     *
     * 探测只在 [Path.UNKNOWN] 发生；锁定 [Path.TRACK] 后只做「失效重建」，不重复探测。
     * 连续 [CREATE_ATTEMPTS] 次创建失败即封存为 [Path.FALLBACK]，之后直接走提示音，
     * 不再有任何创建开销——参数类错误重试无意义，只会让每一声都多一次失败调用。
     */
    private fun obtainTrack(): AudioTrack? {
        // 系统回收过的 track 必须重建：state 变 UNINITIALIZED 是「对象失效」，不是探测失败
        track?.let { t ->
            if (t.state == AudioTrack.STATE_INITIALIZED) return t
            discardTrack("state 已失效")
        }
        synchronized(lock) {
            if (released || path == Path.FALLBACK) return null
            // 走到这里 track 必为 null：上面的失效检查已 discard，且只有音频线程写 track
            val created = createTrack()
            if (created == null) {
                createFailures++
                if (createFailures >= CREATE_ATTEMPTS) {
                    path = Path.FALLBACK
                    Log.w(TAG, "连续 $createFailures 次创建失败，本次运行永久改用提示音")
                }
                return null
            }
            createFailures = 0
            track = created
            if (path != Path.TRACK) {
                path = Path.TRACK
                Log.i(TAG, "音频路径锁定：AudioTrack 合成音")
            }
            return created
        }
    }

    /** 新建 AudioTrack；成功返回实例，失败返回 null 并打日志（不再静默吞异常） */
    private fun createTrack(): AudioTrack? {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // getMinBufferSize 返回「字节」，出错时返回负的错误码。
            // 取 2 倍最小缓冲（通常 40~80ms）：足够防 underrun，又不拖长延迟。
            // 旧实现是 coerceAtLeast(sampleRate / 4) = 11025 字节 = 5512.5 帧 ≈ 125ms，
            // 既把延迟下限抬到 125ms，又因为有半帧余量而可能被系统拒收——这是「几乎每次
            // 都嘟嘟声」最可能的直接原因。这里改为「兜底值 + 按帧对齐」，不再出现奇数字节。
            val base = if (minBuf > 0) minBuf else SAMPLE_RATE / 25 * FRAME_BYTES   // 兜底 40ms
            val bufBytes = ((base * 2) / FRAME_BYTES * FRAME_BYTES)
                .coerceAtLeast(FRAME_BYTES * 1024)   // 至少 1024 帧 ≈ 23ms
            val t = AudioTrack(
                attrs, fmt, bufBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "AudioTrack 就绪：minBuf=$minBuf bytes, buffer=$bufBytes bytes")
                return t
            }
            Log.w(TAG, "AudioTrack 未初始化（state=${t.state}, buffer=$bufBytes），降级为提示音")
            try { t.release() } catch (_: Exception) {}
            return null
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack 创建失败，降级为提示音：${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /** 丢弃 track 实例（暂停 → 清缓冲 → 释放，三步顺序不能反） */
    private fun discardTrack(reason: String) {
        synchronized(lock) {
            val t = track ?: return
            track = null
            Log.w(TAG, "AudioTrack 丢弃（$reason）")
            try { t.pause(); t.flush(); t.release() } catch (_: Exception) {}
        }
    }

    /**
     * 把整段 PCM 写进 AudioTrack。
     *
     * 旧实现是「先 play() 再阻塞 write()」：起播瞬间缓冲是空的，必然 underrun 一次，
     * 表现为每声之前有一小段静音。这里改为先写满缓冲再起播。
     * 写入用 WRITE_NON_BLOCKING + 轮询，既能在被新音效取代时立刻退出（代次变了），
     * 也不会把 `release()` 卡死在一个永不返回的 `write()` 上。
     *
     * @return true = 正常播完或被取代；false = 写入出错，调用方应降级
     */
    private fun writeAll(t: AudioTrack, buf: ShortArray, gen: Int): Boolean {
        try {
            // 非播放状态下先清空残留缓冲。
            //
            // pause / stop 期间写入的数据 AudioTrack 不会消费，会一直堆在环形缓冲里，
            // 等下一次 play() 成功时连同新音效一起播出来——听感就是「这一声没响，
            // 下一次连响两声」。播放中不能 flush（会把正在发声的波形截断成爆音），
            // 但非播放状态下缓冲里的都是「卡住没播」的死数据，清掉正是目的。
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try { t.flush() } catch (_: Exception) {}
            }
            var off = 0
            val startMs = SystemClock.uptimeMillis()
            while (off < buf.size) {
                // 被更新的音效取代：补一段淡出再退出。直接 return 会让波形从中间断掉，
                // 听感上是一声「啪」的爆音；淡出 12ms 后自然收尾。
                if (released || gen != generation.get()) {
                    if (off > 0) fadeOut(t, buf, off)
                    return true
                }
                if (SystemClock.uptimeMillis() - startMs > MAX_WRITE_MS) {
                    Log.w(TAG, "写入超时（${buf.size} 采样），放弃剩余部分")
                    if (off > 0) fadeOut(t, buf, off)
                    return true
                }
                val n = t.write(buf, off, buf.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "AudioTrack.write 返回错误码 $n")
                    return false
                }
                if (n > 0) {
                    off += n
                    continue
                }
                // 缓冲已满：没起播就先起播，已起播就等消费者腾出空间。
                // 这里**不能** flush：flush 会把上一声还没播完的部分直接砍掉（爆音）。
                // 「后来者优先」由上面的代次检查实现，它最多让新音效晚一个缓冲（约 40~80ms）。
                // 每次都查实时 playState：stop() 可能在中途把 track 暂停，
                // 缓存的状态会让这里一直 sleep 到超时
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    startPlayback(t)
                } else {
                    Thread.sleep(WRITE_POLL_MS)
                }
            }
            // 整段比缓冲还短时（一次就写完），此时还没起播
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) startPlayback(t)
            return true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack 写入异常：${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    /**
     * 起播，并复核是否真的进入播放态。
     *
     * `AudioTrack.play()` 不返回错误码，失败时抛 IllegalStateException；但它也可能
     * 悄悄没生效（playState 仍停在 PAUSED）。一旦如此，本段数据就留在缓冲里不播——
     * 用户听到的正是「点了没声音」；等下一次点击 play 成功，两段数据连在一起出来，
     * 就成了「快速响两声」。所以这里必须留痕，否则线上无从判断该不该重建 track。
     */
    private fun startPlayback(t: AudioTrack) {
        try {
            t.play()
            // 部分设备 play() 不立即生效（playState 仍停在 PAUSED）：短等一次再试。否则本段
            // 数据留在缓冲不播，下一次 play 成功时连着播出来就成了「快速响两声」。
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Thread.sleep(16)
                t.play()
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    Log.w(TAG, "AudioTrack.play 仍未生效，playState=${t.playState}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.play 异常：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 把 buf 从 from 起的一小段按线性包络降到 0 后写入（约 12ms），用于中途收尾防爆音 */
    private fun fadeOut(t: AudioTrack, buf: ShortArray, from: Int) {
        val n = minOf(buf.size - from, SAMPLE_RATE / 80)
        if (n <= 0) return
        val tail = ShortArray(n)
        for (i in 0 until n) {
            tail[i] = (buf[from + i] * (1.0 - i.toDouble() / n)).toInt().toShort()
        }
        var off = 0
        while (off < n) {
            val w = try {
                t.write(tail, off, n - off, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: Exception) {
                return
            }
            if (w <= 0) return
            off += w
        }
    }

    /**
     * ToneGenerator 兜底（音色是电信令音，但保证「有声音」而不是静默）。
     *
     * 创建失败一次就不再尝试：[toneFailed] 置位后每一声直接返回。否则设备完全无音频
     * 时，每一次出牌都会重撞一次创建失败并打一条日志，logcat 会被刷满，且没有任何意义。
     */
    private fun playFallback(key: Sfx) {
        val tg = synchronized(lock) {
            if (released || toneFailed) return
            toneGenerator ?: try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 100).also {
                    toneGenerator = it
                    Log.i(TAG, "已启用提示音兜底")
                }
            } catch (e: Exception) {
                toneFailed = true
                Log.w(TAG, "ToneGenerator 创建失败（完全静音，后续不再尝试）：${e.javaClass.simpleName}: ${e.message}")
                null
            }
        } ?: return
        try {
            tg.startTone(key.fallbackTone, key.fallbackDurMs)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator.startTone 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 预热 AudioTrack：提前建好并起播，使首声不必再等「创建 + 起播」延迟。
     *
     * 根本原因：[play] 懒创建 track，而新建 track 的 [AudioTrack.play] 有启动延迟——
     * 首声数据写进缓冲却还没被播放管线消费，下一次点击把新数据续写在它后面，
     * 听感正是「首声没响、第二声一次放俩」。这里在 surface 就绪（已前台、音频服务可用）
     * 时就建好并起播 track，首声直接写进已 PLAYING 的轨道，立即出声。
     *
     * 失败无害：真正的首声会再次尝试，连续失败才会封存提示音；本方法不计入连续失败计数。
     */
    fun prime() {
        if (released) return
        submit {
            val t = obtainTrack()
            if (t == null) {
                // 预热失败只是时机未到（音频服务尚在初始化），不计入连续失败，避免误封提示音
                synchronized(lock) { createFailures = 0 }
            } else if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                startPlayback(t)
            }
        }
    }

    /** 暂停播放并清空缓冲（切后台调用）。保留 track 实例，回前台无需重建。 */
    fun stop() {
        generation.incrementAndGet()   // 让音频线程放弃当前音效
        synchronized(lock) {
            try {
                track?.let { t ->
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                    t.flush()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 回到前台：只重置「已经失败过」的状态，正常路径不改任何东西。
     *
     * 这**不是**定时重试：锁定 [Path.TRACK] 且兜底正常时，这里只做两次 boolean 判断。
     * 它覆盖两个窄场景——冷启动那一瞬音频服务未就绪（三次探测撞在同一毫秒内），
     * 以及后台期间系统回收资源。用户切走再回来时服务通常已恢复，给一次机会比整局
     * 都是嘟嘟声合理；若仍然失败，很快又会封存回去（各多一条日志，不是每声一条）。
     */
    fun onForeground() {
        synchronized(lock) {
            if (released) return
            // 兜底路径曾创建失败：新会话给一次机会（再失败也只会多一条日志，不是每声）
            if (toneFailed) {
                toneFailed = false
                Log.i(TAG, "回前台：重置提示音兜底")
            }
            // 已封存为提示音：重新探测一次合成音
            if (path == Path.FALLBACK) {
                createFailures = 0
                path = Path.UNKNOWN
                Log.i(TAG, "回前台：重新探测一次音频路径")
            }
        }
    }

    /** 彻底释放（视图销毁时调用，不要绑在 surface 上） */
    fun release() {
        if (released) return
        released = true
        generation.incrementAndGet()
        // 顺序很重要：先让音频线程自己退出（它每 4ms 检查一次 released），再动 track。
        // 旧实现是「先 pause+flush 再 await」，那时音频线程可能正卡在 write() 里，
        // pause/flush 与它并发操作同一段缓冲——虽然 AudioTrack 本身线程安全，
        // 但这是没必要的竞争，也是上一版 track 泄漏的同类隐患。
        executor.shutdown()
        try {
            if (!executor.awaitTermination(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "音频线程未在 ${RELEASE_WAIT_MS}ms 内退出，仍继续释放 track")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            val t = track
            track = null
            try {
                t?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                    it.flush()
                    it.release()
                }
            } catch (_: Exception) {}
            try { toneGenerator?.release() } catch (_: Exception) {}
            toneGenerator = null
        }
    }

    // ==================== PCM 合成（仅音频线程调用） ====================

    /**
     * 合成 → 乘该音效音量 → 软限幅 → 16bit。
     *
     * 顺序不能反：音量乘在限幅**之前**。基准增益 1.2 会把峰值抬到 1.1 以上，若先限幅
     * 再乘，等于限完又重新溢出，掉进 `coerceIn` 硬切——削波又回来了。
     *
     * 限幅放在这里而不是每个 synth 内部：合成阶段可以放心叠加多层（叠加本身就会
     * 超过 1.0，中途防溢出只能靠硬削），电平问题统一在出口处理一次。
     */
    private fun pcmFor(key: Sfx): ShortArray = pcmCache.getOrPut(key) {
        val d = synth(key)
        for (i in d.indices) d[i] *= key.gain
        softLimit(d)
        ShortArray(d.size) { i ->
            (d[i] * 32767.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }

    private fun synth(key: Sfx): DoubleArray = when (key) {
        Sfx.TAP_SELECT -> tones(listOf(980.0 to 0.05, 1320.0 to 0.06))
        Sfx.TAP_DESELECT -> tones(listOf(760.0 to 0.05))
        Sfx.CARD_PLAY -> sweepNoise(durSec = 0.13, fStart = 5200.0, fEnd = 1500.0, gain = 0.5) +
                thud(150.0, 0.09, 0.5)
        Sfx.BOMB -> {
            val boom = DoubleArray((SAMPLE_RATE * 0.55).toInt())
            addInto(boom, thud(55.0, 0.45, 0.95))     // 低频冲击
            addInto(boom, thud(110.0, 0.30, 0.55))
            addInto(boom, noiseBurst(0.45, 0.6, decayFast = true))   // 爆炸噪声（前重后轻）
            boom
        }
        Sfx.WIN -> {
            val seq = tones(
                listOf(
                    523.25 to 0.14,   // C5
                    659.25 to 0.14,   // E5
                    783.99 to 0.14,   // G5
                    1046.5 to 0.34    // C6（长一点收尾）
                )
            )
            val tail = mix(listOf(note(523.25, 0.5, 0.25), note(659.25, 0.5, 0.2), note(783.99, 0.5, 0.2)))
            // 尾音和声从琶音 75% 处混入、并向后续延伸。
            //
            // 旧版写的是 concat(seq, offset(tail, (seq.size * 0.75).toInt()))：
            // skip 是 25137，而 tail 只有 22050 —— offset 越界直接返回空数组，
            // 这一整层和声从来没响过；而且 concat 只能接在末尾，本来就表达不了
            // 「从中间叠进去」。这里改成按偏移 addInto，才是注释里写的那个效果。
            val start = (seq.size * 0.75).toInt()
            val out = DoubleArray(maxOf(seq.size, start + tail.size))
            seq.copyInto(out)
            for (i in tail.indices) out[start + i] += tail[i]
            out
        }
        Sfx.LOSE -> tones(listOf(440.0 to 0.16, 349.23 to 0.16, 261.63 to 0.34))
        Sfx.LAST_CARD -> concat(tones(listOf(1560.0 to 0.09)), tones(listOf(1980.0 to 0.12)))
        Sfx.ERROR -> {
            // 双"咚"。旧版有两个坑，合起来就是"报错没声音"：
            //  1) 频率太低：旧版是 160Hz / 140Hz 的纯正弦脉冲。手机小扬声器在 200Hz
            //     以下衰减极快，这种音基本放不出声——合成上确实有波形，喇叭上没输出。
            //  2) 第二声被吞：旧版用 offset 跳过 0.13s 去取第二声，而单声只有 0.12s，
            //     skip 越过数组末尾会返回空数组，于是"双咚"退化成一声 100ms 的闷响。
            // 现在频率抬到 400Hz 以上，并用等长静音留白代替偏移取段。
            // 每声前面叠 8ms 噪声起音：给低频脉冲一个可听的高频边缘（"哒"），
            // 否则纯低频在中低端机型上仍然容易糊掉。
            val a = thud(460.0, 0.09, 0.72)
            addInto(a, tick(0.008, 0.35))
            val b = thud(340.0, 0.12, 0.70)
            addInto(b, tick(0.008, 0.30))
            concat(a, concat(silence(0.035), b))
        }
        Sfx.CONFIRM -> tones(listOf(1046.5 to 0.10))
    }

    // ==================== DSP 基础构件 ====================

    /** 单个音符：基频 + 两次泛音，指数衰减包络，柔和起音防爆音 */
    private fun note(freq: Double, durSec: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        val tau = durSec / 5.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toDouble()
            val env = exp(-t / tau) * (1.0 - exp(-t / 0.004))
            val s = sin(2 * PI * freq * t) +
                    0.35 * sin(2 * PI * freq * 2 * t) +
                    0.12 * sin(2 * PI * freq * 3 * t)
            out[i] = s * env * gain / 1.5
        }
        return out
    }

    /** 一组音符顺序拼接（旋律） */
    private fun tones(seq: List<Pair<Double, Double>>): DoubleArray {
        var result = DoubleArray(0)
        for ((f, d) in seq) result = concat(result, note(f, d, 0.55))
        return result
    }

    /** 低频闷响（无高频成分，像拍桌子）。注意：低于约 200Hz 的成分在手机小扬声器上几乎放不出 */
    private fun thud(freq: Double, durSec: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        val tau = durSec / 6.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toDouble()
            out[i] = sin(2 * PI * freq * t) * exp(-t / tau) * (1.0 - exp(-t / 0.003)) * gain
        }
        return out
    }

    /**
     * 极短白噪声起音（"哒"）。
     *
     * 用来给低频脉冲加一个可听的高频边缘：纯低频在中低端机型的扬声器上衰减很大，
     * 单靠 thud 可能糊成一片或者干脆听不见。8ms 足够被感知为"起音"，又不至于变成爆音。
     */
    private fun tick(durSec: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val k = i / n.toDouble()
            out[i] = (Random.Default.nextDouble() * 2.0 - 1.0) * exp(-k * 4.0) * gain
        }
        return out
    }

    /** 纯静音段，用于两声音之间留白（比用 [offset] 跳段安全：不会因为越界而整段消失） */
    private fun silence(durSec: Double): DoubleArray = DoubleArray((SAMPLE_RATE * durSec).toInt())

    /** 噪声下滑音：模拟纸牌摩擦空气的"唰" */
    private fun sweepNoise(durSec: Double, fStart: Double, fEnd: Double, gain: Double): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        var lp = 0.0
        var phase = 0.0
        for (i in 0 until n) {
            val k = i / n.toDouble()
            val env = sin(PI * k) // 起收皆淡
            // 一阶低通随频率下移，白噪声过带通近似"嘶——"
            val white = Random.Default.nextDouble() * 2.0 - 1.0
            val alpha = (2 * PI * (fStart + (fEnd - fStart) * k * k) / SAMPLE_RATE).coerceAtMost(0.5)
            lp += alpha * (white - lp)
            phase += 2 * PI * 180.0 / SAMPLE_RATE
            out[i] = (lp * 2.2 + 0.15 * sin(phase)) * env * gain
        }
        return out
    }

    /** 噪声爆炸（decayFast：前半段能量集中） */
    private fun noiseBurst(durSec: Double, gain: Double, decayFast: Boolean): DoubleArray {
        val n = (SAMPLE_RATE * durSec).toInt()
        val out = DoubleArray(n)
        var lp = 0.0
        for (i in 0 until n) {
            val k = i / n.toDouble()
            val white = Random.Default.nextDouble() * 2.0 - 1.0
            val alpha = 0.25
            lp += alpha * (white - lp)
            val env = if (decayFast) exp(-k * 6.0) else exp(-k * 3.0)
            out[i] = lp * 2.0 * env * gain
        }
        return out
    }

    // ==================== 数组工具 ====================

    private fun concat(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun offset(a: DoubleArray, skip: Int): DoubleArray =
        if (skip <= 0) a else if (skip >= a.size) DoubleArray(0) else a.copyOfRange(skip, a.size)

    /** 多轨并集叠加（给 WIN 的尾音和声用）。同样不做中途限幅，交给出口 [softLimit] */
    private fun mix(parts: List<DoubleArray>): DoubleArray {
        val len = parts.maxOf { it.size }
        val out = DoubleArray(len)
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    /**
     * 混音叠加：直接相加，**不在中途防溢出**。
     *
     * 旧实现每加一层就 `coerceIn(-1.0, 1.0)`。STRONG_PLAY 三层叠加的峰值是 1.52
     * （+3.6dBFS），第三层一叠上前几十毫秒就被切平——平顶沿等价于方波，带来大量
     * 奇次谐波，听感是"炸"而不是"响"。电平问题交给出口的 [softLimit] 处理。
     */
    private fun addInto(dst: DoubleArray, src: DoubleArray) {
        val n = minOf(dst.size, src.size)
        for (i in 0 until n) dst[i] += src[i]
    }

    /**
     * 软限幅：峰值超过 [limit] 时用 tanh 曲线平滑压回，而不是切平。
     *
     * 与"整体线性缩放"的区别在于只压峰、不动中小电平——爆炸声的冲击力主要来自
     * 前 50ms 的过冲，线性缩放会把它连同尾音一起压掉（实测 RMS -14.7 → -18.7dBFS，
     * 明显变轻）；tanh 饱和则把过冲压扁、把小信号近似线性保留（RMS 反而到 -14.1，
     * 听感更厚实）。
     *
     * [drive] 越大压得越狠、谐波越多。取 2.0：峰值 -0.5dBFS、无平顶样本，
     * 谐波落在低频段，是"厚重"而非"破音"。
     */
    private fun softLimit(buf: DoubleArray, limit: Double = 0.94, drive: Double = 2.0) {
        var peak = 0.0
        for (v in buf) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak <= limit || peak == 0.0) return
        val norm = limit / tanh(drive)
        for (i in buf.indices) buf[i] = norm * tanh(drive * buf[i] / peak)
    }
}
