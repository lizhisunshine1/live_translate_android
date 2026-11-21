package io.github.ztfang.eye.ui.records

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.ztfang.eye.domain.model.RecordSession
import io.github.ztfang.eye.domain.model.TranscriptLine
import io.github.ztfang.eye.domain.model.VocabEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.flowOf

private enum class RecordTab(
    val label: String,
) {
    CURRENT("本次"),
    HISTORY("历史"),
    VOCAB("难词"),
    REVIEW("复习"),
}

private data class WordTarget(
    val word: String,
    val sessionId: Long,
    val lineId: Long,
)

/**
 * 主界面上的「小按钮」：点开记录窗口。
 * 一直显示待复习数量的小角标，让遗忘曲线提醒不需要额外说明也能被发现。
 */
@Composable
fun RecordEntryButton(
    onClick: () -> Unit,
    viewModel: RecordsViewModel = hiltViewModel(),
) {
    val due by viewModel.dueCount.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = "记录",
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "记录本",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (due > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = due.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 记录窗口本体。
 *
 * 一个窗口分四页：本次（正在录的/最近一次）、历史（所有会话，可回看逐句）、
 * 难词（标记过的词 + 语境）、复习（按遗忘曲线到期的卡片）。
 */
@Composable
fun RecordWindowDialog(
    onDismiss: () -> Unit,
    viewModel: RecordsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsState()
    val words by viewModel.words.collectAsState()
    val audioBytes by viewModel.audioBytes.collectAsState()
    val reminderOn by viewModel.reminderEnabled.collectAsState()

    val tts = remember { TtsPlayer(context).also { it.init() } }
    val player = remember { AudioSegmentPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            tts.release()
            player.release()
        }
    }

    var tab by remember { mutableStateOf(RecordTab.CURRENT) }
    var selectedSessionId by remember { mutableStateOf(0L) }
    var historyDetailId by remember { mutableStateOf(0L) }
    var wordTarget by remember { mutableStateOf<WordTarget?>(null) }
    /** 点了删除图标 → 先问「只删语音」还是「连文字一起删」 */
    var deleteTargetId by remember { mutableStateOf(0L) }
    /** 选了「连文字一起删」后的二次确认 */
    var confirmTextDeleteId by remember { mutableStateOf(0L) }
    var confirmCleanAllAudio by remember { mutableStateOf(false) }

    val currentId = remember { viewModel.currentSessionId() }
    LaunchedEffect(sessions.size, currentId) {
        if (selectedSessionId == 0L) {
            selectedSessionId = if (currentId > 0) currentId else sessions.firstOrNull()?.id ?: 0L
        } else if (sessions.none { it.id == selectedSessionId }) {
            selectedSessionId = if (currentId > 0) currentId else sessions.firstOrNull()?.id ?: 0L
        }
    }

    val currentSession = sessions.firstOrNull { it.id == selectedSessionId }
    val detailSession = sessions.firstOrNull { it.id == historyDetailId }

    val currentLines by
        remember(selectedSessionId) {
            if (selectedSessionId > 0) viewModel.linesOf(selectedSessionId) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())

    val detailLines by
        remember(historyDetailId) {
            if (historyDetailId > 0) viewModel.linesOf(historyDetailId) else flowOf(emptyList())
        }.collectAsState(initial = emptyList())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.88f)
                    .padding(vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 20.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                WindowHeader(
                    title = if (historyDetailId > 0) "记录详情" else "记录本",
                    subtitle = when {
                        historyDetailId > 0 -> detailSession?.let { formatDate(it.startTime) } ?: ""
                        tab == RecordTab.CURRENT -> "本次录音的语音与文字"
                        tab == RecordTab.HISTORY -> "共 ${sessions.size} 次记录"
                        tab == RecordTab.VOCAB -> "共 ${words.size} 个难词"
                        else -> "按遗忘曲线安排复习"
                    },
                    onBack = if (historyDetailId > 0) {
                        { historyDetailId = 0 }
                    } else {
                        null
                    },
                    onClose = onDismiss,
                )

                if (historyDetailId == 0L) {
                    TabRow(
                        selectedTabIndex = tab.ordinal,
                        containerColor = Color.Transparent,
                        divider = {},
                    ) {
                        RecordTab.entries.forEach { item ->
                            Tab(
                                selected = tab == item,
                                onClick = { tab = item },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            when (item) {
                                                RecordTab.CURRENT -> Icons.Filled.PlayArrow
                                                RecordTab.HISTORY -> Icons.Filled.History
                                                RecordTab.VOCAB -> Icons.AutoMirrored.Filled.MenuBook
                                                RecordTab.REVIEW -> Icons.Filled.EventAvailable
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(item.label, style = MaterialTheme.typography.labelLarge)
                                    }
                                },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }

                when {
                    historyDetailId > 0 -> {
                        SessionDetailPage(
                            session = detailSession,
                            lines = detailLines,
                            player = player,
                            onWordTap = { word, lineId ->
                                wordTarget = WordTarget(word, historyDetailId, lineId)
                            },
                            onDelete = { deleteTargetId = historyDetailId },
                        )
                    }
                    tab == RecordTab.CURRENT -> {
                        SessionDetailPage(
                            session = currentSession,
                            lines = currentLines,
                            player = player,
                            onWordTap = { word, lineId ->
                                wordTarget = WordTarget(word, selectedSessionId, lineId)
                            },
                            onDelete = { currentSession?.let { deleteTargetId = it.id } },
                            emptyHint = "还没有记录。打开悬浮字幕听一段，文字和语音会自动归档到这里。",
                        )
                    }
                    tab == RecordTab.HISTORY -> {
                        HistoryListPage(
                            sessions = sessions,
                            currentId = currentId,
                            onOpen = { historyDetailId = it },
                        )
                    }
                    tab == RecordTab.VOCAB -> {
                        VocabListPage(
                            words = words,
                            tts = tts,
                            onDelete = { entry -> viewModel.deleteWord(entry) },
                        )
                    }
                    else -> {
                        ReviewPage(
                            words = words,
                            viewModel = viewModel,
                            reminderOn = reminderOn,
                            audioBytes = audioBytes,
                            onToggleReminder = { enabled ->
                                viewModel.setReminderEnabled(enabled)
                                if (enabled) {
                                    ReviewReminder.ensureChannel(context)
                                    ReviewReminder.schedule(context)
                                } else {
                                    ReviewReminder.cancel(context)
                                }
                            },
                            onCleanAudio = { confirmCleanAllAudio = true },
                        )
                    }
                }
            }
        }
    }

    wordTarget?.let { target ->
        WordAnnotationSheet(
            word = target.word,
            sessionId = target.sessionId,
            lineId = target.lineId,
            viewModel = viewModel,
            tts = tts,
            onDismiss = { wordTarget = null },
        )
    }

    if (deleteTargetId > 0) {
        DeleteChoiceDialog(
            onDismiss = { deleteTargetId = 0 },
            onDeleteAudio = {
                val id = deleteTargetId
                deleteTargetId = 0
                viewModel.deleteAudio(id)
                Toast.makeText(context, "语音已删除，文字保留", Toast.LENGTH_SHORT).show()
            },
            onDeleteAll = {
                confirmTextDeleteId = deleteTargetId
                deleteTargetId = 0
            },
        )
    }

    if (confirmTextDeleteId > 0) {
        AlertDialog(
            onDismissRequest = { confirmTextDeleteId = 0 },
            title = { Text("连文字一起删除？") },
            text = {
                Text(
                    "这次记录的全部内容会被删除：语音、英文原文、译文，以及基于这些句子的难词语境。\n此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = confirmTextDeleteId
                        confirmTextDeleteId = 0
                        viewModel.deleteSession(id)
                        Toast.makeText(context, "整条记录已删除", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("确认删除文字", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTextDeleteId = 0 }) { Text("取消") }
            },
        )
    }

    if (confirmCleanAllAudio) {
        AlertDialog(
            onDismissRequest = { confirmCleanAllAudio = false },
            title = { Text("清理全部语音？") },
            text = { Text("会删掉所有记录的语音文件，共 ${formatSize(audioBytes)}；原文、译文、难词都保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCleanAllAudio = false
                        viewModel.deleteAllAudio()
                        Toast.makeText(context, "语音已清理，文字保留", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("清理语音", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanAllAudio = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 删除入口的默认选项：只删语音。
 *
 * 用户口径：管理界面里点删除，默认删的是语音，文字要保留（难词/复习都靠文字）。
 * 想连文字一起删，必须在这里主动选第二项，再经过一次确认。
 */
@Composable
private fun DeleteChoiceDialog(
    onDismiss: () -> Unit,
    onDeleteAudio: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除什么？") },
        text = {
            Column {
                Text(
                    "默认只删这一条的语音，英文原文和译文都会保留。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))
                DeleteOption(
                    title = "只删语音（默认）",
                    desc = "录音文件删掉，文字留着继续复习，可省空间",
                    emphasized = true,
                    onClick = onDeleteAudio,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DeleteOption(
                    title = "删除整条记录",
                    desc = "语音、原文、译文全删，需要再次确认",
                    emphasized = false,
                    onClick = onDeleteAll,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteOption(
    title: String,
    desc: String,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color =
            if (emphasized) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color =
                    if (emphasized) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String =
    when {
        bytes <= 0L -> "0 MB"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

@Composable
private fun WindowHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
        }
    }
}

// ============================== 会话逐句页 ==============================

@Composable
private fun SessionDetailPage(
    session: RecordSession?,
    lines: List<TranscriptLine>,
    player: AudioSegmentPlayer,
    onWordTap: (String, Long) -> Unit,
    onDelete: () -> Unit,
    emptyHint: String = "这次记录没有内容。",
) {
    val isLive = session?.isOngoing == true
    val audioPath =
        if (isLive) {
            null // 正在写的 wav 头还是 0，播放器读不了；等会话结束再回放
        } else {
            session?.audioPath?.takeIf { java.io.File(it).exists() }
        }
    var playingLineId by remember { mutableStateOf(0L) }
    var playingAll by remember { mutableStateOf(false) }

    if (session == null) {
        EmptyState(emptyHint)
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatDate(session.startTime) + if (session.isOngoing) " · 录音中" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${session.sourceLanguage} → ${session.targetLanguage} · " +
                                "${lines.size} 句 · ${formatDuration(session.effectiveDurationMs(System.currentTimeMillis()))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (audioPath != null) {
                        IconButton(
                            onClick = {
                                if (playingAll) {
                                    player.stop()
                                    playingAll = false
                                    playingLineId = 0
                                } else {
                                    player.playAll(audioPath)
                                    playingAll = true
                                    playingLineId = 0
                                }
                            },
                        ) {
                            Icon(
                                if (playingAll) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                                contentDescription = "播放整段语音",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onDelete, enabled = !isLive) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = if (isLive) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                if (audioPath == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            if (isLive) {
                                "正在录音，结束后可以把这次的语音整段回放。"
                            } else {
                                "这条的语音已删除，原文与译文都还在，难词和复习照常可用。"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (lines.isEmpty()) {
            EmptyState("这次还没有识别到句子。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 4.dp,
                    bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lines, key = { it.id }) { line ->
                    LineCard(
                        line = line,
                        audioPath = audioPath,
                        isPlaying = playingLineId == line.id,
                        onPlay = {
                            if (audioPath == null) return@LineCard
                            if (playingLineId == line.id) {
                                player.stop()
                                playingLineId = 0
                            } else {
                                player.play(audioPath, line.startMs, line.endMs) { playingLineId = 0 }
                                playingLineId = line.id
                                playingAll = false
                            }
                        },
                        onWordTap = { word -> onWordTap(word, line.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LineCard(
    line: TranscriptLine,
    audioPath: String?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onWordTap: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%02d".format(line.seq + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatClock(line.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (audioPath != null) {
                    IconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = "播放这句",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            ClickableWordsText(
                text = line.sourceText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                onWordTap = onWordTap,
            )
            if (line.translatedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = line.translatedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "（翻译中…）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ============================== 历史列表 ==============================

@Composable
private fun HistoryListPage(
    sessions: List<RecordSession>,
    currentId: Long,
    onOpen: (Long) -> Unit,
) {
    if (sessions.isEmpty()) {
        EmptyState("还没有历史记录。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Surface(
                onClick = { onOpen(session.id) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatDate(session.startTime),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (session.id == currentId) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "进行中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${session.sourceLanguage} → ${session.targetLanguage} · ${session.lineCount} 句 · " +
                                formatDuration(session.effectiveDurationMs(System.currentTimeMillis())) +
                                if (session.audioPath != null) " · 有声" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ============================== 难词本 ==============================

@Composable
private fun VocabListPage(
    words: List<VocabEntry>,
    tts: TtsPlayer,
    onDelete: (VocabEntry) -> Unit,
) {
    if (words.isEmpty()) {
        EmptyState("还没有标记难词。在记录里点任意一个单词即可标记，系统会自动保存前后语境。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(words, key = { it.id }) { entry ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.word,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (entry.phonetic.isNotBlank()) {
                            Text(
                                text = "/${entry.phonetic}/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        AssistChip(
                            onClick = { tts.speak(entry.word) },
                            label = { Text("发音", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            ),
                        )
                        IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (entry.meaning.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = entry.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (entry.sentence.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ContextBlock("当时那句", entry.sentence, highlight = entry.word)
                        if (entry.contextBefore.isNotBlank()) ContextBlock("前两句", entry.contextBefore)
                        if (entry.contextAfter.isNotBlank()) ContextBlock("后两句", entry.contextAfter)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "复习进度：${io.github.ztfang.eye.domain.model.ReviewScheduler.stageLabel(entry)} · " +
                            "下次 ${formatDate(entry.dueAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextBlock(
    label: String,
    text: String,
    highlight: String = "",
) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            text.split("\n").forEach { sentence ->
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ============================== 复习 ==============================

@Composable
private fun ReviewPage(
    words: List<VocabEntry>,
    viewModel: RecordsViewModel,
    reminderOn: Boolean,
    audioBytes: Long,
    onToggleReminder: (Boolean) -> Unit,
    onCleanAudio: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val due by remember(now) { viewModel.dueWords(now) }.collectAsState(initial = emptyList())
    var reviewing by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<VocabEntry>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    if (reviewing && queue.isEmpty()) {
        queue = due
        index = 0
        revealed = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        // 设置区：语音占用 + 复习提醒
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("语音占用 ${formatSize(audioBytes)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "录音默认全部保留，文字永不自动删除；空间紧张时可清掉语音，文字不受影响",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (audioBytes > 0) {
                        TextButton(onClick = onCleanAudio) {
                            Text("清理", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("每日复习提醒", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "每天 20:30 提醒一次到期的难词",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = reminderOn, onCheckedChange = onToggleReminder)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!reviewing) {
            Text(
                text = if (due.isEmpty()) "现在没有到期的难词" else "今天有 ${due.size} 个难词到期",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "间隔按 SM-2 遗忘曲线自动调整：答对一次隔一天，再对隔三天、七天…… 答错就回到 10 分钟后再看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (due.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        queue = due
                        index = 0
                        revealed = false
                        reviewing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始复习（${due.size}）") }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(due.take(10), key = { it.id }) { entry ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("• ${entry.word}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = io.github.ztfang.eye.domain.model.ReviewScheduler.stageLabel(entry),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            val entry = queue.getOrNull(index)
            if (entry == null) {
                Text("今天的复习完成了 🎉", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "共复习 ${queue.size} 个词，下一次到期时会再提醒你。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        reviewing = false
                        now = System.currentTimeMillis()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("结束复习") }
            } else {
                LinearProgressIndicator(
                    progress = { (index.toFloat() / queue.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                if (entry.phonetic.isNotBlank()) {
                    Text(
                        text = "/${entry.phonetic}/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (entry.sentence.isNotBlank()) {
                    Text(
                        text = entry.sentence,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (!revealed) {
                    Button(
                        onClick = { revealed = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("显示释义与语境") }
                } else {
                    Text(entry.meaning, style = MaterialTheme.typography.bodyLarge)
                    if (entry.contextBefore.isNotBlank() || entry.contextAfter.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (entry.contextBefore.isNotBlank()) ContextBlock("前两句", entry.contextBefore)
                        if (entry.contextAfter.isNotBlank()) ContextBlock("后两句", entry.contextAfter)
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradeButton("忘记", Modifier.weight(1f), MaterialTheme.colorScheme.error) {
                            viewModel.grade(entry, io.github.ztfang.eye.domain.model.ReviewScheduler.QUALITY_FORGOT)
                            index += 1
                            revealed = false
                        }
                        GradeButton("模糊", Modifier.weight(1f), MaterialTheme.colorScheme.tertiary) {
                            viewModel.grade(entry, io.github.ztfang.eye.domain.model.ReviewScheduler.QUALITY_HARD)
                            index += 1
                            revealed = false
                        }
                        GradeButton("记得", Modifier.weight(1f), MaterialTheme.colorScheme.primary) {
                            viewModel.grade(entry, io.github.ztfang.eye.domain.model.ReviewScheduler.QUALITY_GOOD)
                            index += 1
                            revealed = false
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.grade(entry, io.github.ztfang.eye.domain.model.ReviewScheduler.QUALITY_EASY); index += 1; revealed = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("秒懂（拉长间隔）") }
                    Text(
                        text = "第 ${index + 1} / ${queue.size} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun GradeButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = color.copy(alpha = 0.15f)),
    ) { Text(text, color = color, fontWeight = FontWeight.SemiBold) }
}

// ============================== 小工具 ==============================

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(28.dp),
        )
    }
}

private val shortDateFormatter = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())
private val clockFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatDate(ts: Long): String = if (ts <= 0) "—" else shortDateFormatter.format(Date(ts))

private fun formatClock(ts: Long): String = clockFormatter.format(Date(ts))

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min >= 60) "${min / 60}h${min % 60}m" else "%d:%02d".format(min, sec)
}
