package org.kotlinchat.client.text

/** One clickable URL inside message text; [start] is inclusive and [end] exclusive. */
data class MessageLink(
    val start: Int,
    val end: Int,
    val url: String,
)

/**
 * Finds explicit http/https URLs in chat text.
 *
 * Deliberately conservative: only schemes that are safe to hand to a browser are recognised.
 * Bare domains, `www.` shorthand and other schemes (`mailto:`, `file:`, `javascript:`) stay
 * plain text. A sentence-ending period or an unmatched closing bracket is not part of the URL.
 */
object MessageLinks {

    private const val HTTPS = "https://"
    private const val HTTP = "http://"
    private const val TRAILING_PUNCTUATION = ".,;:!?'\""
    private const val SCAN_STOP = "<>\"'`"

    fun find(text: String): List<MessageLink> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<MessageLink>()
        var search = 0
        while (search < text.length) {
            val start = findScheme(text, search)
            if (start < 0) break
            val rawEnd = scanEnd(text, start)
            val end = trimTrailing(text, start, rawEnd)
            if (isValid(text, start, end)) {
                result += MessageLink(start, end, text.substring(start, end))
            }
            search = if (end > start) end else start + 1
        }
        return result
    }

    private fun findScheme(text: String, from: Int): Int {
        var index = from
        while (index <= text.length - HTTP.length) {
            val match = matches(text, index, HTTPS) || matches(text, index, HTTP)
            // Do not turn "xhttp://..." into a link in the middle of a word.
            if (match && (index == 0 || !text[index - 1].isLetterOrDigit())) return index
            index++
        }
        return -1
    }

    private fun scanEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length) {
            val c = text[end]
            if (c.isWhitespace() || c in SCAN_STOP) break
            end++
        }
        return end
    }

    private fun trimTrailing(text: String, start: Int, rawEnd: Int): Int {
        var end = rawEnd
        var changed = true
        while (changed && end > start) {
            changed = false
            when (text[end - 1]) {
                in TRAILING_PUNCTUATION -> {
                    end--
                    changed = true
                }

                ')' -> if (count(text, start, end, '(') < count(text, start, end, ')')) {
                    end--
                    changed = true
                }

                ']' -> if (count(text, start, end, '[') < count(text, start, end, ']')) {
                    end--
                    changed = true
                }

                '}' -> if (count(text, start, end, '{') < count(text, start, end, '}')) {
                    end--
                    changed = true
                }
            }
        }
        return end
    }

    private fun isValid(text: String, start: Int, end: Int): Boolean {
        val scheme = when {
            matches(text, start, HTTPS) -> HTTPS
            matches(text, start, HTTP) -> HTTP
            else -> return false
        }
        val authorityStart = start + scheme.length
        if (end <= authorityStart) return false
        var authorityEnd = authorityStart
        while (authorityEnd < end && text[authorityEnd] !in "/?#") authorityEnd++
        return authorityEnd > authorityStart
    }

    private fun count(text: String, start: Int, end: Int, needle: Char): Int {
        var found = 0
        for (index in start until end) {
            if (text[index] == needle) found++
        }
        return found
    }

    private fun matches(text: String, index: Int, prefix: String): Boolean {
        if (index + prefix.length > text.length) return false
        for (offset in prefix.indices) {
            if (text[index + offset].lowercaseChar() != prefix[offset]) return false
        }
        return true
    }
}
