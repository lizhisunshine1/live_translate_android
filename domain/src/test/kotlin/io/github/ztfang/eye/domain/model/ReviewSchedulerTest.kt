package io.github.ztfang.eye.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM-2 排程的硬性约束：答对间隔递增、答错立刻回到 10 分钟、掌握后不再提醒 */
class ReviewSchedulerTest {
    private val now = 1_700_000_000_000L

    private fun newEntry() =
        VocabEntry(
            word = "ubiquitous",
            normalized = "ubiquitous",
            createdAt = now,
            dueAt = ReviewScheduler.initialDueAt(now),
            ease = 2.5,
        )

    @Test
    fun `new word is due in ten minutes`() {
        assertEquals(now + 10 * 60_000L, ReviewScheduler.initialDueAt(now))
    }

    @Test
    fun `answer forgot resets to ten minutes and records lapse`() {
        val entry = newEntry().copy(repetitions = 3, intervalDays = 7, ease = 2.6)
        val next = ReviewScheduler.schedule(entry, ReviewScheduler.QUALITY_FORGOT, now)
        assertEquals(now + 10 * 60_000L, next.dueAt)
        assertEquals(0, next.repetitions)
        assertEquals(entry.lapses + 1, next.lapses)
        assertTrue(next.ease < entry.ease)
    }

    @Test
    fun `first correct answer comes back in ten minutes then one day`() {
        val first = ReviewScheduler.schedule(newEntry(), ReviewScheduler.QUALITY_GOOD, now)
        assertEquals(1, first.repetitions)
        assertEquals(now + 10 * 60_000L, first.dueAt)

        val second = ReviewScheduler.schedule(first, ReviewScheduler.QUALITY_GOOD, now + 10 * 60_000L)
        assertEquals(2, second.repetitions)
        assertEquals(24 * 3600_000L, second.dueAt - (now + 10 * 60_000L))
    }

    @Test
    fun `intervals keep growing for consecutive correct answers`() {
        var entry = newEntry()
        var t = now
        val intervals = mutableListOf<Long>()
        repeat(6) {
            entry = ReviewScheduler.schedule(entry, ReviewScheduler.QUALITY_GOOD, t)
            intervals.add(entry.dueAt - t)
            t = entry.dueAt
        }
        // 前两次是 10 分钟 / 1 天，之后按 ease 递增
        assertTrue("intervals should grow: $intervals", intervals[2] < intervals[3])
        assertTrue("intervals should grow: $intervals", intervals[3] <= intervals[4])
    }

    @Test
    fun `easy answer stretches interval compared with good`() {
        val entry = newEntry().copy(repetitions = 3, intervalDays = 7)
        val good = ReviewScheduler.schedule(entry, ReviewScheduler.QUALITY_GOOD, now)
        val easy = ReviewScheduler.schedule(entry, ReviewScheduler.QUALITY_EASY, now)
        assertTrue(easy.dueAt > good.dueAt)
    }

    @Test
    fun `mastered after five correct and long interval`() {
        var entry = newEntry()
        var t = now
        repeat(6) {
            entry = ReviewScheduler.schedule(entry, ReviewScheduler.QUALITY_EASY, t)
            t = entry.dueAt
        }
        assertTrue(entry.mastered)
        assertTrue(ReviewScheduler.stageLabel(entry) == "已掌握")
    }
}
