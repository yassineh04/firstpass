package io.github.yassineh04.firstpass.eval

import java.util.Locale

/** Number formats of the evaluation output, identical in every locale. */
internal object Numbers {
    const val NOT_AVAILABLE = "n/a"
    private const val PERCENT = 100

    /** `75 %`, or `n/a` when there is nothing to measure. */
    fun percent(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.0f %%", it * PERCENT) } ?: NOT_AVAILABLE

    /** `0.5`, or `n/a` when there is nothing to measure. */
    fun decimal(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: NOT_AVAILABLE

    /** `1.20`. */
    fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
