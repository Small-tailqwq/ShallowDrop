package io.github.smalltailqwq.shallowdrop.ui.markdown

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.smalltailqwq.shallowdrop.core.model.TextCitation

private val PREVIEW_MARKDOWN = """
# 二次方程

对 \( ax^2 + bx + c = 0 \)（\(a \neq 0\)），判别式 ${'$'}\Delta = b^2 - 4ac${'$'}，价格 ${'$'}5 到 ${'$'}10 不是公式。[citation:1]

$$
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
$$

## 要点

1. **粗体**、*斜体*、~~删除线~~、`行内代码`
2. 链接 [DeepSeek](https://www.deepseek.com) 与 https://example.com[citation:2]
   - 嵌套项
     - 更深一层
- [x] 已完成
- [ ] 待办

> 引用块
> 第二行

---

| 方法 | 复杂度 | 说明 |
|:---|:---:|---:|
| 公式法 | \(O(1)\) | 直接求根 |
| 牛顿迭代 | \(O(\log n)\) | 需要初值，适合高精度场景下的数值求解 |

```python
def solve(a, b, c):
    # 返回两个根
    d = (b * b - 4 * a * c) ** 0.5
    return (-b + d) / (2 * a), (-b - d) / (2 * a)
```
""".trimIndent()

private val PREVIEW_CITATIONS = listOf(
    TextCitation(url = "https://zh.wikipedia.org/wiki/一元二次方程", title = "一元二次方程"),
    TextCitation(url = "https://example.com", title = "Example"),
)

@Preview(name = "Markdown", showBackground = true, widthDp = 360, heightDp = 1400)
@Composable
private fun MarkdownTextPreview() {
    MaterialTheme {
        Surface {
            MarkdownText(
                markdown = PREVIEW_MARKDOWN,
                citations = PREVIEW_CITATIONS,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(name = "Markdown 深色", showBackground = true, widthDp = 360, heightDp = 1400)
@Composable
private fun MarkdownTextDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            MarkdownText(
                markdown = PREVIEW_MARKDOWN,
                citations = PREVIEW_CITATIONS,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

@Preview(name = "流式未闭合", showBackground = true, widthDp = 360)
@Composable
private fun MarkdownStreamingPreview() {
    MaterialTheme {
        Surface {
            MarkdownText(
                markdown = "正在生成 **粗体\n\n```kotlin\nfun main() {\n    println(\"hi\")\n\n$$\n\\sum_{i=1}^{n} i",
                streaming = true,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
