package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile

/**
 * Estimates the number of tokens of a text: about 4 characters per token, plus a 20 % margin,
 * rounded up. Integer arithmetic only, so that the planner never rounds below the real need.
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 4
    private const val HUNDRED_PERCENT = 100
    private const val MARGIN_PERCENT = 20

    /** Cost of the `=== File: <path> (<status>, <language>)` line around each file, without the path. */
    private const val FILE_HEADER_TOKENS = 12

    private const val SCALED_CHARS_PER_TOKEN = CHARS_PER_TOKEN * HUNDRED_PERCENT
    private const val SCALED_COST_PER_CHAR = HUNDRED_PERCENT + MARGIN_PERCENT

    fun estimate(chars: Int): Int =
        ((chars.toLong() * SCALED_COST_PER_CHAR + SCALED_CHARS_PER_TOKEN - 1) / SCALED_CHARS_PER_TOKEN).toInt()

    fun estimate(text: String): Int = estimate(text.length)

    /** The largest number of characters whose estimate does not exceed [tokens]. */
    fun maxChars(tokens: Int): Int = (tokens.toLong() * SCALED_CHARS_PER_TOKEN / SCALED_COST_PER_CHAR).toInt()

    /** A file as the prompt shows it: its header line plus its annotated hunks. */
    fun estimate(file: AnnotatedFile): Int = fileOverhead(file.file.path) + estimate(file.render())

    internal fun fileOverhead(path: String): Int = estimate(path) + FILE_HEADER_TOKENS
}
