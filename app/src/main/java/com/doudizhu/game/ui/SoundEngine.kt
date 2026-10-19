package com.doudizhu.game.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 程序化音效引擎：用正弦波 + 指数衰减包络实时合成 PCM，经 AudioTrack (USAGE_GAME) 播放。
 *
 * 相比直接使用 ToneGenerator 的电信令音，合成音更圆润细腻（柔和起音、泛音层次、
 * 低频"厚度"），且全部在后台线程生成与写入，不阻塞绘制/触摸线程。
 * 内部保留 ToneGenerator 作为兜底：AudioTrack 初始化失败时自动退回提示音模式。
 */
class SoundEngine(context: Context) {

    private val sampleRate = 44100
    private var track: AudioTrack? = null
    private var useAudioTrack = false
    private var toneGenerator: ToneGenerator? = null

    /** 串行执行音频任务的单线程调度器（避免并发写 AudioTrack） */
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoundEngine").apply { isDaemon = true }
    }

    init {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                @Suppress("DEPRECATION")
                val minBuf = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack(attrs, fmt, minBuf.coerceAtLeast(sampleRate / 4),
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    track = t
                    useAudioTrack = true
                } else {
                    t.release()
                }
            }
        } catch (_: Exception) {}
        if (!useAudioTrack) {
            try {
                // 音量：ToneGenerator 音量参数上限为 100（系统流满档），仅作兜底
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            } catch (_: Exception) {}
        }
    }

    val available: Boolean get() = useAudioTrack || toneGenerator != null

    // ==================== 对外音效接口（均异步，调用线程零开销） ====================

    /** 轻触选牌：两声上行短"嗒"音，干脆清晰 */
    fun tapSelect() = play(ToneGenerator.TONE_PROP_ACK, 40) {
        tones(listOf(980.0 to 0.05, 1320.0 to 0.06))
    }

    /** 取消选牌：一声下行短音，与选中形成方向对比 */
    fun tapDeselect() = play(ToneGenerator.TONE_PROP_NACK, 30) { tones(listOf(760.0 to 0.05)) }

    /** 出牌：模拟纸牌甩上桌面的"唰"声（噪声滑音 + 低频闷响） */
    fun cardPlay() = play(ToneGenerator.TONE_PROP_BEEP, 80) {
        sweepNoise(durSec = 0.13, fStart = 5200.0, fEnd = 1500.0, gain = 0.5) + thud(150.0, 0.09, 0.5)
    }

    /** 炸弹/火箭：低频轰鸣 + 渐弱噪声爆炸 */
    fun bomb() = play(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300) {
        val boom = DoubleArray((sampleRate * 0.55).toInt())
        // 低频冲击
        addInto(boom, thud(55.0, 0.45, 0.95))
        addInto(boom, thud(110.0, 0.30, 0.55))
        // 爆炸噪声（前重后轻）
        addInto(boom, noiseBurst(0.45, 0.6, decayFast = true))
        boom
    }

    /** 胜利：上行琶音 C-E-G-C + 长尾和音，明亮庆祝感 */
    fun win() = play(ToneGenerator.TONE_CDMA_PIP, 200) {
        val seq = tones(listOf(
            523.25 to 0.14,   // C5
            659.25 to 0.14,   // E5
            783.99 to 0.14,   // G5
            1046.5 to 0.34    // C6（长一点收尾）
        ))
        val tail = mix(listOf(note(523.25, 0.5, 0.25), note(659.25, 0.5, 0.2), note(783.99, 0.5, 0.2)))
        concat(seq, offset(tail, (seq.size * 0.75).toInt()))
    }

    /** 失败：下行三连音，柔和不刺耳（长辈友好） */
    fun lose() = play(ToneGenerator.TONE_PROP_NACK, 200) {
        tones(listOf(440.0 to 0.16, 349.23 to 0.16, 261.63 to 0.34))
    }

    /** 剩最后一张提醒：双音急促提示 */
    fun lastCardAlert() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260) {
        concat(tones(listOf(1560.0 to 0.09)), tones(listOf(1980.0 to 0.12)))
    }

    /** 错误：低沉双"咚"，避免尖锐蜂鸣 */
    fun error() = play(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 150) {
        concat(thud(160.0, 0.10, 0.7), offset(thud(140.0, 0.12, 0.7), (sampleRate * 0.13).toInt()))
    }

    /** 叫分/确认：清脆的单击"叮" */
    fun confirm() = play(ToneGenerator.TONE_PROP_ACK, 50) { tones(listOf(1046.5 to 0.10)) }

    // ==================== 兜底与生命周期 ====================

    /** 合成路径：后台线程生成 PCM 并写入 AudioTrack；失败时退回 ToneGenerator 兜底音 */
    private fun play(fallbackTone: Int, fallbackDur: Int, gen: () -> DoubleArray) {
        if (useAudioTrack) {
            executor.execute {
                try {
                    val pcm = gen()
                    val buf = ShortArray(pcm.size)
                    for (i in pcm.indices) {
                        val v = (pcm[i] * 32767.0).coerceIn(-32767.0, 32767.0)
                        buf[i] = v.toInt().toShort()
                    }
                    val t = track ?: return@execute
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    t.write(buf, 0, buf.size)
                } catch (_: Exception) {
                    playFallback(fallbackTone, fallbackDur)
                }
            }
        } else {
            playFallback(fallbackTone, fallbackDur)
        }
    }

    /** ToneGenerator 兜底（同样在后台线程执行，避免 startTone 阻塞调用方） */
    private fun playFallback(tone: Int, dur: Int) {
        executor.execute {
            try { toneGenerator?.startTone(tone, dur) } catch (_: Exception) {}
        }
    }

    fun stop() {
        try {
            track?.let { it.stop(); it.flush() }
        } catch (_: Exception) {}
    }

    fun release() {
        executor.shutdown()
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
    }

    // ==================== DSP 基础构件 ====================

    /** 单个音符：基频 + 两次泛音，指数衰减包络，柔和起音防爆音 */
    private fun note(freq: Double, durSec: Double, gain: Double): DoubleArray {
        val n = (sampleRate * durSec).toInt()
        val out = DoubleArray(n)
        val tau = durSec / 5.0
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
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

    /** 低频闷响（无高频成分，像拍桌子） */
    private fun thud(freq: Double, durSec: Double, gain: Double): DoubleArray {
        val n = (sampleRate * durSec).toInt()
        val out = DoubleArray(n)
        val tau = durSec / 6.0
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            out[i] = sin(2 * PI * freq * t) * exp(-t / tau) * (1.0 - exp(-t / 0.003)) * gain
        }
        return out
    }

    /** 噪声下滑音：模拟纸牌摩擦空气的"唰" */
    private fun sweepNoise(durSec: Double, fStart: Double, fEnd: Double, gain: Double): DoubleArray {
        val n = (sampleRate * durSec).toInt()
        val out = DoubleArray(n)
        var lp = 0.0
        var phase = 0.0
        for (i in 0 until n) {
            val k = i / n.toDouble()
            val env = sin(PI * k) // 起收皆淡
            // 一阶低通随频率下移，白噪声过带通近似"嘶——"
            val white = Random.Default.nextDouble() * 2.0 - 1.0
            val alpha = (2 * PI * (fStart + (fEnd - fStart) * k * k) / sampleRate).coerceAtMost(0.5)
            lp += alpha * (white - lp)
            phase += 2 * PI * 180.0 / sampleRate
            out[i] = (lp * 2.2 + 0.15 * sin(phase)) * env * gain
        }
        return out
    }

    /** 噪声爆炸（decayFast：前半段能量集中） */
    private fun noiseBurst(durSec: Double, gain: Double, decayFast: Boolean): DoubleArray {
        val n = (sampleRate * durSec).toInt()
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

    private fun mix(parts: List<DoubleArray>): DoubleArray {
        val len = parts.maxOf { it.size }
        val out = DoubleArray(len)
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out.map { it.coerceIn(-1.0, 1.0) }.toDoubleArray()
    }

    private fun addInto(dst: DoubleArray, src: DoubleArray) {
        val n = minOf(dst.size, src.size)
        for (i in 0 until n) dst[i] = (dst[i] + src[i]).coerceIn(-1.0, 1.0)
    }
}
