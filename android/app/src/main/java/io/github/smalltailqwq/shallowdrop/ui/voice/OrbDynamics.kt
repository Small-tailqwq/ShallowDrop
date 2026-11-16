package io.github.smalltailqwq.shallowdrop.ui.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 光球的神态；语音对话各阶段与音色预览各对应一种 */
enum class OrbMood {
    /** 接通中：最大，缓慢呼吸 */
    Connecting,

    /** 聆听：随输入音量起伏，向外扩散波纹 */
    Listening,

    /** 思考：收小，流体加速，一颗光点绕球环行 */
    Thinking,

    /** 说话：随朗读响度跳动 */
    Speaking,

    /** 静音：褪色，几乎静止 */
    Muted,

    /** 已停止（出错待重试）：褪色并缩到最小 */
    Stopped,

    /** 音色预览：安静地流动 */
    Preview,
}

/**
 * 光球的动效状态。每帧 [step] 一次，把离散的 [OrbMood] 与跳变的音量化成连续量，绘制只读这些量：
 *
 * - 神态之间不硬切：各权重按指数趋近，大小用弹簧过渡。
 * - 音量起得快、落得慢，开口立刻有反应，停顿时不抖动；说话时落得快一些，跟上朗读的音节。
 * - 流动、波纹、环绕都是累加的相位，加减速只改变速率，纹理不会跳变。
 *
 * 不依赖 Compose，可单独测试。
 */
class OrbDynamics(initialScale: Float = ENTER_SCALE) {
    /** 流体相位 */
    var flow = 0f
        private set

    /** 波纹相位，0..2π */
    var ripple = 0f
        private set

    /** 思考光点的角度，0..2π */
    var spin = 0f
        private set

    /** 平滑后的音量，0..1 */
    var level = 0f
        private set

    /** 聆听权重，0..1：波纹的强弱 */
    var listening = 0f
        private set

    /** 思考权重，0..1：光点的强弱 */
    var thinking = 0f
        private set

    /** 褪色权重，0..1 */
    var dim = 0f
        private set

    /** 相对标称大小的缩放 */
    var scale = initialScale
        private set

    private var velocity = 0f
    private var energy = 0.4f
    private var clock = 0f

    /**
     * @param seconds 距上一帧的时间
     * @param input 当前神态下的音量 0..1（聆听为麦克风，说话为朗读），其余神态忽略
     * @param speed 音色的流动快慢
     * @param pressed 手指按住
     */
    fun step(seconds: Float, mood: OrbMood, input: Float, speed: Float = 1f, pressed: Boolean = false) {
        // 掉帧或从后台回来时间隔很大：封顶后分成小步，弹簧不会发散
        var remaining = seconds.coerceIn(0f, MAX_FRAME)
        while (remaining > 0f) {
            val h = min(remaining, SUBSTEP)
            advance(h, mood, input.coerceIn(0f, 1f), speed, pressed)
            remaining -= h
        }
    }

    private fun advance(h: Float, mood: OrbMood, input: Float, speed: Float, pressed: Boolean) {
        clock += h
        val heard = if (mood == OrbMood.Listening || mood == OrbMood.Speaking) input else 0f
        val release = if (mood == OrbMood.Speaking) RELEASE_SPEAKING else RELEASE
        level += (heard - level) * ease(h, if (heard > level) ATTACK else release)
        listening += ((if (mood == OrbMood.Listening) 1f else 0f) - listening) * ease(h, 7f)
        thinking += ((if (mood == OrbMood.Thinking) 1f else 0f) - thinking) * ease(h, 5f)
        dim += ((if (mood == OrbMood.Muted || mood == OrbMood.Stopped) 1f else 0f) - dim) * ease(h, 5f)

        val targetEnergy = when (mood) {
            OrbMood.Connecting -> 0.55f
            OrbMood.Listening -> 0.35f + 0.5f * level
            OrbMood.Thinking -> 0.95f
            OrbMood.Speaking -> 0.5f + 0.7f * level
            OrbMood.Muted -> 0.08f
            OrbMood.Stopped -> 0.04f
            OrbMood.Preview -> 0.4f
        }
        energy += (targetEnergy - energy) * ease(h, 3f)
        flow += h * speed * (0.12f + 0.6f * energy)
        ripple = (ripple + h * listening * (1.6f + 8f * level)) % TAU
        spin = (spin + h * SPIN) % TAU

        var target = when (mood) {
            OrbMood.Connecting -> 1f + 0.03f * sin(clock * 2.2f)
            OrbMood.Listening -> 0.86f + 0.10f * level
            OrbMood.Thinking -> 0.80f + 0.03f * sin(clock * 3f)
            OrbMood.Speaking -> 0.86f + 0.18f * level
            OrbMood.Muted -> 0.76f
            OrbMood.Stopped -> 0.72f
            OrbMood.Preview -> 0.92f + 0.015f * sin(clock * 1.6f)
        }
        if (pressed) target *= PRESSED
        // 略欠阻尼的弹簧：切换神态时带一点回弹
        velocity += (STIFFNESS * (target - scale) - DAMPING * velocity) * h
        scale += velocity * h
    }

    private fun ease(h: Float, rate: Float) = 1f - exp(-h * rate)

    companion object {
        /** 进入页面时从这个大小弹出 */
        const val ENTER_SCALE = 0.6f

        private const val MAX_FRAME = 0.1f
        private const val SUBSTEP = 1f / 60f
        private const val ATTACK = 16f
        private const val RELEASE = 4.5f
        private const val RELEASE_SPEAKING = 9f

        /** 光点约 1.7 秒绕一圈：思考往往只有一两秒，也要看得出在转 */
        private const val SPIN = 3.7f
        private const val PRESSED = 0.95f
        private const val STIFFNESS = 220f
        private val DAMPING = 2f * 0.8f * sqrt(STIFFNESS)
        private const val TAU = (2 * PI).toFloat()
    }
}
