package io.github.ztfang.eye.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.ztfang.eye.domain.model.DictEntry
import android.widget.Toast

/**
 * 点词后的「悬停注释」卡片：读音、音标、释义，一键标为难点。
 *
 * 为什么用对话框而不是贴着单词的 Popup：字幕文本会随布局折行，
 * 贴边弹层在屏幕下沿会被裁掉；居中的卡片在横竖屏、不同字号下都不会错位。
 */
@Composable
fun WordAnnotationSheet(
    word: String,
    sessionId: Long,
    lineId: Long,
    viewModel: RecordsViewModel,
    tts: TtsPlayer,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val allWords by viewModel.words.collectAsState()
    var entry by remember(word) { mutableStateOf<DictEntry?>(null) }
    var looked by remember(word) { mutableStateOf(false) }
    val normalized = remember(word) { word.lowercase().trim('\'', '-', '’') }
    val alreadyMarked = allWords.any { it.normalized == normalized }

    LaunchedEffect(word) {
        entry = runCatching { viewModel.lookup(normalized) }.getOrNull()
        looked = true
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry?.word ?: word,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val phonetic = entry?.phonetic.orEmpty()
                        Text(
                            text = if (phonetic.isNotBlank()) "/$phonetic/" else "（词典中暂无音标）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (entry?.phonetic.isNullOrBlank() && entry == null) {
                                Toast.makeText(context, "离线词典未收录该词，仍可发音", Toast.LENGTH_SHORT).show()
                            }
                            tts.speak(entry?.word ?: word)
                        },
                    ) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = "发音",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val meaning = entry?.translation.orEmpty()
                if (meaning.isNotBlank()) {
                    Text(
                        text = meaning.replace(" | ", "\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                    )
                } else if (looked) {
                    Text(
                        text = "离线词典未收录这个词，可直接标记为难词，复习时会显示原句语境。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry?.lematized == true) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "（按原形查得）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (alreadyMarked) {
                                Toast.makeText(context, "已在难词本", Toast.LENGTH_SHORT).show()
                                onDismiss()
                                return@Button
                            }
                            viewModel.markWord(
                                word = normalized,
                                displayWord = word,
                                sessionId = sessionId,
                                lineId = lineId,
                                phonetic = entry?.phonetic.orEmpty(),
                                meaning = meaning.replace(" | ", "\n"),
                            ) { ok ->
                                Toast.makeText(
                                    context,
                                    if (ok) "已加入难词本，10 分钟后开始复习" else "这个词已在难词本里",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            onDismiss()
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    if (alreadyMarked) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                            ),
                    ) {
                        Icon(
                            if (alreadyMarked) Icons.Filled.Check else Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (alreadyMarked) "已在难词本" else "标记为难词")
                    }
                }
            }
        }
    }
}
