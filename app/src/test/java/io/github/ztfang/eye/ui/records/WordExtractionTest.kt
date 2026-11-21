package io.github.ztfang.eye.ui.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 点词取词：必须能取到被点的整词，光标的标点/空格位置也要能吸附到最近的单词 */
class WordExtractionTest {
    private val sentence = "The ubiquitous 'smartphone' has changed how-we live."

    @Test
    fun `tap in the middle of a word returns the whole word`() {
        val index = sentence.indexOf("quit") + 2
        assertEquals("ubiquitous", extractWordAt(sentence, index))
    }

    @Test
    fun `tap on space snaps to a nearby word`() {
        assertEquals("ubiquitous", extractWordAt(sentence, sentence.indexOf(' ') + 1))
    }

    @Test
    fun `apostrophe inside quotes is kept`() {
        assertEquals("smartphone", extractWordAt(sentence, sentence.indexOf("smart") + 1))
    }

    @Test
    fun `hyphenated word keeps its hyphen`() {
        assertEquals("how-we", extractWordAt(sentence, sentence.indexOf("how") + 1))
    }

    @Test
    fun `empty text returns null`() {
        assertNull(extractWordAt("", 0))
    }

    @Test
    fun `punctuation only returns null`() {
        assertNull(extractWordAt(" ... ", 2))
    }
}
