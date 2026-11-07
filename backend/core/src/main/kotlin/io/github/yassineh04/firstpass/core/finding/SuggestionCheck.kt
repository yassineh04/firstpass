package io.github.yassineh04.firstpass.core.finding

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile

/**
 * Keeps a code suggestion only when applying it is safe. GitHub replaces exactly the commented
 * lines with the suggestion, so one that repeats the line just above or just below the range would
 * duplicate that line. A suggestion that is too long is dropped rather than cut, because cut code
 * is broken code, and one that changes nothing is noise.
 *
 * Dropping a suggestion keeps the comment: the worst case is a lost one-click fix, never a lost
 * finding. A legitimate fix that ends like the next line (a closing brace, for example) is dropped
 * too; this is accepted, since a duplicated line is worse than a missing suggestion.
 */
internal object SuggestionCheck {
    const val MAX_LENGTH = 1200

    /** The suggestion for new-file lines [startLine] to [endLine] of [file], or null when unsafe. */
    fun safe(
        suggestion: String,
        file: AnnotatedFile?,
        startLine: Int,
        endLine: Int,
    ): String? =
        suggestion.takeIf {
            it.length <= MAX_LENGTH && (file == null || fitsRange(it, file, startLine, endLine))
        }

    private fun fitsRange(
        suggestion: String,
        file: AnnotatedFile,
        startLine: Int,
        endLine: Int,
    ): Boolean {
        val lines = suggestion.lines()
        val repeatsAbove = sameCode(lines.firstOrNull { it.isNotBlank() }, file.contentAt(startLine - 1))
        val repeatsBelow = sameCode(lines.lastOrNull { it.isNotBlank() }, file.contentAt(endLine + 1))
        val current = (startLine..endLine).mapNotNull(file::contentAt)
        val unchanged =
            current.size == endLine - startLine + 1 && trimmed(suggestion) == trimmed(current.joinToString("\n"))
        return !repeatsAbove && !repeatsBelow && !unchanged
    }

    /** Same code apart from whitespace, which models often change when they echo a line. */
    private fun sameCode(
        suggested: String?,
        existing: String?,
    ): Boolean =
        suggested != null &&
            existing != null &&
            suggested.filterNot(Char::isWhitespace) == existing.filterNot(Char::isWhitespace)

    /** The text without trailing spaces on each line, nor trailing blank lines. */
    private fun trimmed(text: String): String = text.lines().joinToString("\n") { it.trimEnd() }.trimEnd()
}
