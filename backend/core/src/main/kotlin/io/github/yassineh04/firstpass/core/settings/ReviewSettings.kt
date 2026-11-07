package io.github.yassineh04.firstpass.core.settings

/**
 * Effective review settings of a repository: built-in defaults, then dashboard settings,
 * then the `.firstpass.yml` file of the base branch. Fields are added as features need them.
 *
 * [include] and [exclude] are glob patterns on repository paths. An empty [include] means
 * every file is a candidate.
 */
data class ReviewSettings(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)
