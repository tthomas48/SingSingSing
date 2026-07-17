package com.singsingsing.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LrcParserTest {
    @Test
    fun parseMultiLineLrcWithFractionalSeconds() {
        val lrc = """
            [00:12.00]First line
            [00:15.50]Second line
            [01:02.345]Third line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)

        assertThat(lines).hasSize(3)
        assertThat(lines[0].timeMs).isEqualTo(12_000L)
        assertThat(lines[0].text).isEqualTo("First line")
        assertThat(lines[1].timeMs).isEqualTo(15_500L)
        assertThat(lines[1].text).isEqualTo("Second line")
        assertThat(lines[2].timeMs).isEqualTo(62_345L)
        assertThat(lines[2].text).isEqualTo("Third line")
    }

    @Test
    fun parseWholeSecondsWithoutFraction() {
        val lines = LrcParser.parse("[01:05]No fraction here")
        assertThat(lines).hasSize(1)
        assertThat(lines[0].timeMs).isEqualTo(65_000L)
        assertThat(lines[0].text).isEqualTo("No fraction here")
    }

    @Test
    fun parseMultipleTimestampsOnOneLine() {
        val lines = LrcParser.parse("[00:10.00][00:20.00]Shared text")
        assertThat(lines).hasSize(2)
        assertThat(lines.map { it.timeMs }).containsExactly(10_000L, 20_000L).inOrder()
        assertThat(lines.map { it.text }).containsExactly("Shared text", "Shared text")
    }

    @Test
    fun plainTextWithNoTimestampsReturnsEmpty() {
        assertThat(LrcParser.parse("Just some words\nWithout times")).isEmpty()
    }

    @Test
    fun nullBlankAndEmptyReturnEmpty() {
        assertThat(LrcParser.parse(null)).isEmpty()
        assertThat(LrcParser.parse("")).isEmpty()
        assertThat(LrcParser.parse("   \n  ")).isEmpty()
    }

    @Test
    fun skipsBlankTimedLinesAndMetadataStyleEmptyTags() {
        val lrc = """
            [ar:Artist]
            [ti:Title]
            [00:05.00]
            [00:10.00]Real lyric
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertThat(lines).hasSize(1)
        assertThat(lines[0].text).isEqualTo("Real lyric")
        assertThat(lines[0].timeMs).isEqualTo(10_000L)
    }

    @Test
    fun sortsByTimeWhenOutOfOrder() {
        val lrc = """
            [00:20.00]Later
            [00:10.00]Earlier
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertThat(lines.map { it.text }).containsExactly("Earlier", "Later").inOrder()
    }
}
