package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import java.nio.file.Path

/** Arguments of the `review` command: one patch file and optional `--name value` pairs. */
internal data class ReviewArguments(
    val patch: Path,
    val provider: String? = null,
    val model: String? = null,
    val title: String? = null,
    val language: ReviewLanguage = ReviewLanguage.EN,
    val output: Path? = null,
) {
    companion object {
        private val OPTIONS = setOf("--provider", "--model", "--title", "--language", "--output")

        fun parse(args: List<String>): ReviewArguments {
            val parsed = CommandLine.parse(args, OPTIONS)
            val patch =
                when (parsed.positional.size) {
                    0 -> throw CliError("Give the patch file to review, for example: review changes.patch")

                    1 -> parsed.positional.single()

                    else -> throw CliError(
                        "Give only one patch file at a time, got: ${parsed.positional.joinToString(" ")}",
                    )
                }
            return ReviewArguments(
                patch = Path.of(patch),
                provider = parsed.options["--provider"],
                model = parsed.options["--model"],
                title = parsed.options["--title"],
                language = parsed.options["--language"]?.let(::language) ?: ReviewLanguage.EN,
                output = parsed.options["--output"]?.let { Path.of(it) },
            )
        }

        private fun language(value: String): ReviewLanguage =
            ReviewLanguage.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw CliError("The language must be en or fr, got: $value")
    }
}
