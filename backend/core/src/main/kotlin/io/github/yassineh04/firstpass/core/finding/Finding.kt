package io.github.yassineh04.firstpass.core.finding

/**
 * One comment as returned by the model. The llm adapter has already checked it against the
 * JSON Schema; core still treats its content as untrusted.
 */
data class ModelComment(
    val path: String,
    val startLine: Int?,
    val line: Int,
    val severity: Severity,
    val category: Category,
    val title: String,
    val body: String,
    val suggestion: String?,
    val confidence: Double,
)

/** The model's answer for one batch. */
data class ModelReview(
    val summary: String,
    val comments: List<ModelComment>,
)

/** A checked and cleaned finding, ready to be published inline or listed in the summary. */
data class Finding(
    val path: String,
    val startLine: Int?,
    val line: Int,
    val severity: Severity,
    val category: Category,
    val title: String,
    val body: String,
    val suggestion: String?,
    val confidence: Double,
    val fingerprint: String,
)
