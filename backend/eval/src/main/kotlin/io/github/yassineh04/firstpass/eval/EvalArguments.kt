package io.github.yassineh04.firstpass.eval

import java.nio.file.Path

/** Arguments of the `eval` command. [pauseSeconds] null means: 4 seconds for a remote provider, none for fake. */
internal data class EvalArguments(
    val provider: String? = null,
    val model: String? = null,
    val runs: Int = 2,
    val cases: Path = Path.of("eval", "cases"),
    val output: Path? = null,
    val pauseSeconds: Int? = null,
) {
    companion object {
        private val OPTIONS = setOf("--provider", "--model", "--runs", "--cases", "--output", "--pause")

        fun parse(args: List<String>): EvalArguments {
            val parsed = CommandLine.parse(args, OPTIONS)
            if (parsed.positional.isNotEmpty()) {
                throw CliError(
                    "The eval command takes no file, got: ${parsed.positional.joinToString(" ")}. " +
                        "Use --cases to choose the case folder.",
                )
            }
            val defaults = EvalArguments()
            return EvalArguments(
                provider = parsed.options["--provider"],
                model = parsed.options["--model"],
                runs = parsed.options["--runs"]?.let { number("--runs", it, minimum = 1) } ?: defaults.runs,
                cases = parsed.options["--cases"]?.let { Path.of(it) } ?: defaults.cases,
                output = parsed.options["--output"]?.let { Path.of(it) },
                pauseSeconds = parsed.options["--pause"]?.let { number("--pause", it, minimum = 0) },
            )
        }

        private fun number(
            option: String,
            value: String,
            minimum: Int,
        ): Int =
            value.toIntOrNull()?.takeIf { it >= minimum }
                ?: throw CliError("$option must be a whole number of at least $minimum, got: $value")
    }
}
