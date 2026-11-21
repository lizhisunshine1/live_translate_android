package io.github.ztfang.eye.ui.records

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle

/**
 * 整段文本里「点哪个词就选哪个词」。
 *
 * 实现要点：不在文本里给每个词做 FlowRow（那会丢掉原来的断行/标点排版，
 * 中英混排时尤其明显），而是保留单个 Text，点击时用 [TextLayoutResult]
 * 把坐标换算成字符偏移，再把偏移向两侧扩展到整个单词。
 */
@Composable
fun ClickableWordsText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onWordTap: (word: String) -> Unit,
) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        modifier =
            modifier.pointerInput(text) {
                detectTapGestures { offset ->
                    val result = layout ?: return@detectTapGestures
                    val index = result.getOffsetForPosition(offset)
                    extractWordAt(text, index)?.let(onWordTap)
                }
            },
        style = style,
        color = color,
        onTextLayout = { layout = it },
    )
}

/** 供预览/单测使用的纯函数：从 [index] 向两侧扩展到完整单词 */
fun extractWordAt(
    text: String,
    index: Int,
): String? {
    if (text.isEmpty()) return null
    val idx = index.coerceIn(0, text.length - 1)
    fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || (c == '-' && idx > 0)
    var start = idx
    var end = idx + 1
    if (!text[idx].isLetter()) {
        // 点在标点/空格上：向右找最近一个字母，找不到再向左找
        var i = idx
        while (i < text.length && !text[i].isLetter()) i++
        if (i >= text.length) {
            i = idx
            while (i >= 0 && !text[i].isLetter()) i--
            if (i < 0) return null
        }
        start = i
        end = i + 1
    }
    while (start > 0 && isWordChar(text[start - 1])) start--
    while (end < text.length && isWordChar(text[end])) end++
    val word = text.substring(start, end).trim('\'', '-', '.', ',', '!', '?', ';', ':', '"', '(', ')')
    return word.takeIf { it.isNotEmpty() && it.any { c -> c.isLetter() } }
}

/** 把句子按空白切成词（给「标记难词」弹窗高亮当前词用） */
fun splitWords(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotBlank() }
