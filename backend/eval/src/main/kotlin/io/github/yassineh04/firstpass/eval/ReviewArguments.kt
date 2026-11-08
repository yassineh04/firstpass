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
            val options = mutableMapOf<String, String>()
            val positional = mutableListOf<String>()
            var index = 0
            while (index < args.size) {
                if (args[index].startsWith("--")) {
                    options[args[index]] = valueAfter(args, index)
                    index += 2
                } else {
                    positional += args[index]
                    index++
                }
            }
            val patch =
                when (positional.size) {
                    0 -> throw CliError("Give the patch file to review, for example: review changes.patch")
                    1 -> positional.single()
                    else -> throw CliError("Give only one patch file at a time, got: ${positional.joinToString(" ")}")
                }
            return ReviewArguments(
                patch = Path.of(patch),
                provider = options["--provider"],
                model = options["--model"],
                title = options["--title"],
                language = options["--language"]?.let(::language) ?: ReviewLanguage.EN,
                output = options["--output"]?.let { Path.of(it) },
            )
        }

        private fun valueAfter(
            args: List<String>,
            index: Int,
        ): String {
            val option = args[index]
            if (option !in OPTIONS) throw CliError("Unknown option: $option")
            return args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
                ?: throw CliError("Missing value after $option")
        }

        private fun language(value: String): ReviewLanguage =
            ReviewLanguage.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw CliError("The language must be en or fr, got: $value")
    }
}
