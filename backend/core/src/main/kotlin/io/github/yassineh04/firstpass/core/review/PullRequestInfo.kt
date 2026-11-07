package io.github.yassineh04.firstpass.core.review

/** What the model is told about the pull request itself. Title and description are untrusted. */
data class PullRequestInfo(
    val number: Int,
    val title: String,
    val description: String?,
)
