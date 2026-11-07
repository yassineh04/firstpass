package io.github.yassineh04.firstpass.core.redaction

import io.github.yassineh04.firstpass.core.diff.AddedLine
import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.diff.ContextLine
import io.github.yassineh04.firstpass.core.diff.Hunk
import io.github.yassineh04.firstpass.core.diff.RemovedLine
import io.github.yassineh04.firstpass.core.diff.withContent

/** Families of secrets detected before anything is sent to a model. [label] appears in `[REDACTED:label]`. */
enum class SecretType(
    val label: String,
) {
    AWS_ACCESS_KEY("aws-access-key"),
    PRIVATE_KEY("private-key"),
    GITHUB_TOKEN("github-token"),
    SLACK_TOKEN("slack-token"),
    JWT("jwt"),
    CONNECTION_STRING("connection-string"),
    ASSIGNED_SECRET("assigned-secret"),
}

/** Where a secret was found on an added line. It never holds the secret value itself. */
data class DetectedSecret(
    val path: String,
    val line: Int,
    val type: SecretType,
)

/** A file whose lines and patch no longer contain any detected secret. */
data class RedactedFile(
    val file: AnnotatedFile,
    val secrets: List<DetectedSecret>,
)

/**
 * Deterministic secret detection, run before any text is sent to a model. Secrets are
 * replaced on every line sent (added, context and removed), but only those on added lines
 * are reported: they are the ones the pull request introduces.
 */
object SecretRedactor {
    fun redact(file: AnnotatedFile): RedactedFile {
        val secrets = mutableListOf<DetectedSecret>()
        val hunks =
            file.hunks.map { hunk ->
                val redactions = LineRedactor.redactLines(hunk.lines.map { it.content })
                val lines =
                    hunk.lines.zip(redactions) { line, redaction ->
                        if (line is AddedLine) {
                            redaction.types.forEach { secrets += DetectedSecret(file.file.path, line.newLine, it) }
                        }
                        line.withContent(redaction.content)
                    }
                hunk.copy(header = redactText(hunk.header), lines = lines)
            }
        // The raw patch is rebuilt from the redacted lines, so no copy of a secret is left behind.
        val diffFile = file.file.copy(patch = hunks.joinToString("\n", transform = ::toPatch))
        return RedactedFile(AnnotatedFile(diffFile, hunks), secrets)
    }

    /** Redacts free text, such as the title or the description of a pull request. */
    fun redactText(text: String): String = LineRedactor.redactLines(text.lines()).joinToString("\n") { it.content }

    private fun toPatch(hunk: Hunk): String =
        (
            listOf(hunk.header) +
                hunk.lines.map { line ->
                    when (line) {
                        is AddedLine -> "+${line.content}"
                        is ContextLine -> " ${line.content}"
                        is RemovedLine -> "-${line.content}"
                    }
                }
        ).joinToString("\n")
}
