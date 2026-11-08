package io.github.yassineh04.firstpass.core.batch

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile

/**
 * Estimates the number of tokens of a text: 4 characters per token plus a 50 % margin, about 2.7
 * characters per token, rounded up. Integer arithmetic only, so that the planner never rounds
 * below the real need.
 *
 * Calibrated on 2026-11-08 against Gemini's counts: prompts made mostly of annotated code take
 * about 2.9 characters per token, English prose about 3.6. The margin keeps the estimate at least
 * 5 % above the code measurements; prose is overestimated, which only makes batches a bit smaller.
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 4
    private const val HUNDRED_PERCENT = 100
    private const val MARGIN_PERCENT = 50

    private const val SCALED_CHARS_PER_TOKEN = CHARS_PER_TOKEN * HUNDRED_PERCENT
    private const val SCALED_COST_PER_CHAR = HUNDRED_PERCENT + MARGIN_PERCENT

    /**
     * Characters of the `=== File: <path> (<status>, <language>)` line around each file, without
     * the path, for the longest status and language (`modified`, `typescript`), newline included.
     */
    private const val FILE_HEADER_CHARS = 34

    fun estimate(chars: Int): Int =
        ((chars.toLong() * SCALED_COST_PER_CHAR + SCALED_CHARS_PER_TOKEN - 1) / SCALED_CHARS_PER_TOKEN).toInt()

    fun estimate(text: String): Int = estimate(text.length)

    /** The largest number of characters whose estimate does not exceed [tokens]. */
    fun maxChars(tokens: Int): Int = (tokens.toLong() * SCALED_CHARS_PER_TOKEN / SCALED_COST_PER_CHAR).toInt()

    /** A file as the prompt shows it: its header line plus its annotated hunks. */
    fun estimate(file: AnnotatedFile): Int = fileOverhead(file.file.path) + estimate(file.render())

    internal fun fileOverhead(path: String): Int = estimate(path) + estimate(FILE_HEADER_CHARS)
}
