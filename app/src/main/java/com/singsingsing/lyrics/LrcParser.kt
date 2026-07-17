package com.singsingsing.lyrics

import com.singsingsing.party.LyricsLine

object LrcParser {
    private val LINE_TIME = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val META_TAGS = setOf("ar", "ti", "al", "by", "offset", "re", "ve", "length")

    /**
     * Parses standard LRC (`[mm:ss.xx]text`) into timed lyric lines.
     * Metadata tags and blank lines are skipped. Multiple timestamps on one
     * line produce one [LyricsLine] per timestamp.
     */
    fun parse(syncedLyrics: String?): List<LyricsLine> {
        if (syncedLyrics.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LyricsLine>()
        for (raw in syncedLyrics.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val matches = LINE_TIME.findAll(line).toList()
            if (matches.isEmpty()) continue

            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            if (isMetadataTag(text)) continue

            for (match in matches) {
                val minutes = match.groupValues[1].toInt()
                val seconds = match.groupValues[2].toInt()
                val fraction = match.groupValues[3]
                val millis = fractionToMillis(fraction)
                val timeMs = minutes * 60_000L + seconds * 1_000L + millis
                lines += LyricsLine(timeMs = timeMs, text = text)
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun fractionToMillis(fraction: String): Long {
        if (fraction.isEmpty()) return 0L
        val padded = fraction.padEnd(3, '0').take(3)
        return padded.toLong()
    }

    private fun isMetadataTag(text: String): Boolean {
        val colon = text.indexOf(':')
        if (colon <= 0) return false
        val tag = text.substring(0, colon).lowercase()
        return tag in META_TAGS
    }
}
