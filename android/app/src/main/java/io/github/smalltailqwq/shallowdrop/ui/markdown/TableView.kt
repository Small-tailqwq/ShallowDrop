package io.github.smalltailqwq.shallowdrop.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * GFM 表格：整体可横向滚动。列宽取该列单元格的最大固有宽度（限制在 [MIN_COLUMN, MAX_COLUMN]），
 * 行高取该行单元格在列宽下的最大高度；每行底部一条分隔线（对照 Web 版 markdown.css）。
 */
@Composable
internal fun TableView(table: MdBlock.Table, context: MarkdownContext, modifier: Modifier = Modifier) {
    val style = context.style
    val columns = table.header.size
    // 测量阶段写入、绘制阶段读取的分隔线位置（同一帧内先布局后绘制）
    val rowBottoms = remember(table) { IntArray(table.rows.size + 1) }
    val lineWidth = remember { intArrayOf(0) }
    Box(modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                (listOf(table.header) + table.rows.map { it.take(columns) }).forEachIndexed { rowIndex, row ->
                    row.forEachIndexed { col, cell ->
                        val align = when (table.alignments.getOrNull(col)) {
                            TableAlign.Center -> TextAlign.Center
                            TableAlign.Right -> TextAlign.End
                            else -> TextAlign.Start
                        }
                        MarkdownInlineText(
                            inlines = cell.inlines,
                            context = context,
                            textStyle = if (rowIndex == 0) style.tableHeader else style.tableBody,
                            textAlign = align,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            modifier = Modifier.drawBehind {
                val stroke = 1.dp.toPx()
                rowBottoms.forEachIndexed { i, y ->
                    drawLine(
                        color = if (i == 0) style.secondary.copy(alpha = 0.5f) else style.divider,
                        start = Offset(0f, y - stroke / 2),
                        end = Offset(lineWidth[0].toFloat(), y - stroke / 2),
                        strokeWidth = stroke,
                    )
                }
            },
        ) { measurables, _ ->
            val rows = table.rows.size + 1
            val minColumn = MIN_COLUMN.roundToPx()
            val maxColumn = MAX_COLUMN.roundToPx()
            val widths = IntArray(columns) { col ->
                (0 until rows).maxOf { row -> measurables[row * columns + col].maxIntrinsicWidth(Constraints.Infinity) }
                    .coerceIn(minColumn, maxColumn)
            }
            val heights = IntArray(rows) { row ->
                (0 until columns).maxOf { col -> measurables[row * columns + col].minIntrinsicHeight(widths[col]) }
            }
            val placeables = measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints.fixed(widths[index % columns], heights[index / columns]))
            }
            val totalWidth = widths.sum()
            var y = 0
            for (row in 0 until rows) {
                y += heights[row]
                rowBottoms[row] = y
            }
            lineWidth[0] = totalWidth
            layout(totalWidth, y) {
                var top = 0
                for (row in 0 until rows) {
                    var left = 0
                    for (col in 0 until columns) {
                        placeables[row * columns + col].place(left, top)
                        left += widths[col]
                    }
                    top += heights[row]
                }
            }
        }
    }
}

private val MIN_COLUMN = 72.dp
private val MAX_COLUMN = 280.dp
