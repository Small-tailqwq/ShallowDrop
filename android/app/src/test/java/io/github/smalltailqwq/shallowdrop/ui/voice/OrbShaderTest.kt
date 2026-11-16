package io.github.smalltailqwq.shallowdrop.ui.voice

import android.graphics.RuntimeShader
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 光球着色器是一段字符串，写错了只有运行到语音页才会崩。这里用原生图形模式真的编译一遍，
 * 并核对绘制时设置的每个 uniform 都存在且维数相符。（测试环境是软件渲染，画不了这种着色器，不检查像素。）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OrbShaderTest {
    private val size = 200

    private fun shader(): RuntimeShader = RuntimeShader(ORB_SHADER).apply {
        setFloatUniform("uCenter", size / 2f, size / 2f)
        setFloatUniform("uRadius", size / 4f)
        setFloatUniform("uExtent", size / 2f)
        setFloatUniform("uFlow", 3.2f)
        setFloatUniform("uSeed", 1.7f)
        setFloatUniform("uDetail", 2.3f)
        setFloatUniform("uWarp", 0.95f)
        setFloatUniform("uLight", 1f, 0.96f, 0.94f)
        setFloatUniform("uMid", 1f, 0.72f, 0.66f)
        setFloatUniform("uDeep", 0.84f, 0.45f, 0.65f)
        setFloatUniform("uPulse", 0.5f)
        setFloatUniform("uRipple", 1f)
        setFloatUniform("uRippleAmt", 0.6f)
        setFloatUniform("uSpin", 2f)
        setFloatUniform("uThink", 1f)
        setFloatUniform("uDim", 0f)
        setFloatUniform("uGlow", 1f)
    }

    @Test
    fun environmentReallyCompilesShaders() {
        // 环境若不编译着色器，下面的测试就没有意义
        assertThrows(IllegalArgumentException::class.java) { RuntimeShader("vec4 main(vec2 p) { return nonsense; }") }
        // 同理，设置不存在的 uniform、或维数不符，也必须报错
        assertThrows(IllegalArgumentException::class.java) { shader().setFloatUniform("uNoSuchUniform", 1f) }
        assertThrows(IllegalArgumentException::class.java) { shader().setFloatUniform("uCenter", 1f) }
    }

    @Test
    fun shaderCompilesWithEveryUniformThePainterSets() {
        shader()
    }
}
