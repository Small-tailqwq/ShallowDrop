package io.github.smalltailqwq.shallowdrop.ui.markdown

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 渲染冒烟测试：各类块都能组合、布局、绘制不崩溃；截图写到 build/ 下便于目测 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp-xxhdpi")
class MarkdownTextRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val markdown = """
        # 标题 [citation:1]

        正文 **粗体** *斜体* ~~删除~~ `code` 与 \( \frac{a+b}{2} \)、${'$'}x_i^2${'$'}，价格 ${'$'}5 到 ${'$'}10。[citation:2]

        $$
        \int_0^1 x^2\,dx = \frac{1}{3}
        $$

        1. 有序
           - 嵌套
             - 更深
        - [x] 任务

        > 引用
        > 第二行

        ---

        | 列一 | 列二 |
        |:--|--:|
        | 很长很长的一段单元格内容会折行显示 | 2 |

        ```kotlin
        fun main() = println("hi") // 注释
        ```
    """.trimIndent()

    private val citations = listOf(
        TextCitation(url = "https://a.com", title = "A"),
        TextCitation(url = "https://b.com", title = "B"),
    )

    @Test
    fun rendersAllBlockTypes() {
        var clicked: TextCitation? = null
        compose.setContent {
            MaterialTheme {
                Surface {
                    MarkdownText(
                        markdown = markdown,
                        citations = citations,
                        onCitationClick = { clicked = it },
                        modifier = Modifier.width(400.dp).padding(16.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("2", useUnmergedTree = true).onFirst().performClick()
        assertEquals(citations[1], clicked)
        save("markdown-render.png")
    }

    @Test
    fun streamingUpdatesDoNotCrash() {
        var text by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Surface {
                    MarkdownText(markdown = text, streaming = true, modifier = Modifier.width(400.dp))
                }
            }
        }
        for (end in 1..markdown.length step 7) {
            text = markdown.substring(0, end)
            compose.mainClock.advanceTimeBy(50)
        }
        text = markdown
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/markdown-screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
