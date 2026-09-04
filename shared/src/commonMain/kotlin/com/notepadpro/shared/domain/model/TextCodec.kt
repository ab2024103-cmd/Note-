package com.notepadpro.shared.domain.model

/**
 * Pure text codec helpers shared by every platform (no platform APIs).
 * Keeps CRLF/LF detection and normalization in one boring, testable place.
 */
object TextCodec {

    /** Detects the dominant line ending of a raw text blob. */
    fun detectLineEnding(raw: String): LineEnding {
        var crlf = 0
        var lf = 0
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c == '\n') {
                if (i > 0 && raw[i - 1] == '\r') crlf++ else lf++
            } else if (c == '\r') {
                // lone \r counts as a line break (old Mac); keep count small path
                if (i + 1 >= n || raw[i + 1] != '\n') lf++
            }
            i++
        }
        return if (crlf > lf && crlf > 0) LineEnding.CRLF else LineEnding.LF
    }

    /** Joins plain lines with the requested line ending. */
    fun joinLines(lines: List<EditorLine>, lineEnding: LineEnding): String =
        lines.joinToString(if (lineEnding == LineEnding.CRLF) "\r\n" else "\n") { it.plainText }

    fun joinPlain(lines: List<String>, lineEnding: LineEnding): String =
        lines.joinToString(if (lineEnding == LineEnding.CRLF) "\r\n" else "\n")

    /** Derives a note title from the first non-empty line. */
    fun titleFromLines(lines: List<EditorLine>): String {
        for (line in lines) {
            val t = line.plainText.trim()
            if (t.isNotEmpty()) {
                return if (t.length <= 100) t else t.take(97) + "..."
            }
        }
        return "Untitled"
    }
}
