package io.github.ztfang.eye.domain.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 遗忘曲线复习排程（SM-2 变体）。
 *
 * 为什么用 SM-2 而不是固定「5分钟/30分钟/1天…」表：
 * 固定表不看用户反应，记得牢的词也会被反复塞进复习队列。SM-2 用「反应质量」调节
 * 间隔与难度系数，是 Anki / SuperMemo 一路沿用的经典算法，符合「比较经典的遗忘曲线」要求。
 *
 * 质量分（quality）：
 *  5 = 秒懂
 *  4 = 想了一下才想起
 *  3 = 勉强记得（提示后想起）
 *  2 = 忘了，但看到答案觉得眼熟
 *  1 / 0 = 完全没印象
 */
object ReviewScheduler {
    /** 短间隔（单位：分钟）：第一次答对后当天/次日再看一眼，符合「遗忘先快后慢」 */
    private val EARLY_INTERVALS_MIN = longArrayOf(10, 60 * 24L) // 10 分钟、1 天

    /** 答对后的基础间隔（天）；超出后完全交给 ease 因子 */
    private val STEP_INTERVALS_DAYS = intArrayOf(1, 3, 7, 16, 35)

    const val QUALITY_FORGOT = 2
    const val QUALITY_HARD = 3
    const val QUALITY_GOOD = 4
    const val QUALITY_EASY = 5

    /**
     * 根据本次复习质量计算下一次复习时间。
     *
     * @param entry 当前难词条目
     * @param quality 0..5
     * @param now 当前时间戳
     */
    fun schedule(
        entry: VocabEntry,
        quality: Int,
        now: Long,
    ): VocabEntry {
        val q = quality.coerceIn(0, 5)
        // SM-2 难度系数更新：EF' = EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02))
        val easeDelta = 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)
        val newEase = (entry.ease + easeDelta).coerceIn(1.3, 2.8)

        if (q < QUALITY_HARD) {
            // 答错：间隔归零，明天（或稍后）重来，并记一次遗忘
            val nextIntervalDays = 0
            return entry.copy(
                ease = newEase,
                repetitions = 0,
                lapses = entry.lapses + 1,
                intervalDays = nextIntervalDays,
                dueAt = now + 10 * 60 * 1000L,
                mastered = false,
            )
        }

        val reps = entry.repetitions + 1
        val minutes: Long
        val days: Int
        if (reps == 1) {
            // 第一次答对：10 分钟后复查一次
            minutes = EARLY_INTERVALS_MIN[0]
            days = 0
        } else if (reps == 2) {
            // 第二次答对：1 天后
            minutes = 0
            days = 1
        } else {
            minutes = 0
            val base =
                if (entry.intervalDays > 0) {
                    (entry.intervalDays * newEase).roundToLong()
                } else {
                    STEP_INTERVALS_DAYS[min(reps - 3, STEP_INTERVALS_DAYS.size - 1)].toLong()
                }
            days = base.coerceIn(1, 365).toInt()
        }

        // 「秒懂」额外放宽 20%，「勉强」缩短 20%
        val adjustedDays =
            when (q) {
                QUALITY_EASY -> (days * 1.2).roundToLong()
                QUALITY_HARD -> max(1, (days * 0.8).roundToLong())
                else -> days.toLong()
            }.coerceIn(1, 365)

        val intervalMs =
            if (minutes > 0) {
                minutes * 60_000L
            } else {
                adjustedDays * 24L * 60L * 60L * 1000L
            }

        // 连续 5 次答对且间隔已超过 60 天 → 标记掌握，停止打扰
        val mastered = reps >= 5 && adjustedDays >= 60

        return entry.copy(
            ease = newEase,
            repetitions = reps,
            intervalDays = if (minutes > 0) 0 else adjustedDays.toInt(),
            dueAt = now + intervalMs,
            mastered = mastered,
        )
    }

    /** 新标记的词：10 分钟后第一次进入复习队列 */
    fun initialDueAt(now: Long): Long = now + EARLY_INTERVALS_MIN[0] * 60_000L

    /** 复习阶段的人类可读描述 */
    fun stageLabel(entry: VocabEntry): String =
        when {
            entry.mastered -> "已掌握"
            entry.repetitions == 0 && entry.lapses == 0 -> "新词"
            entry.intervalDays >= 30 -> "长期记忆"
            entry.intervalDays >= 7 -> "巩固中"
            entry.intervalDays >= 1 -> "短期记忆"
            else -> "初记"
        }
}
