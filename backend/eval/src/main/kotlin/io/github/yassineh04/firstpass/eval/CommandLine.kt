package io.github.yassineh04.firstpass.eval

/** Arguments split into positional values and `--name value` options. */
internal class ParsedArguments(
    val positional: List<String>,
    val options: Map<String, String>,
)

internal object CommandLine {
    /** Splits [args]; an option outside [allowed], or without a value, is a usage error. */
    fun parse(
        args: List<String>,
        allowed: Set<String>,
    ): ParsedArguments {
        val options = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            if (args[index].startsWith("--")) {
                options[args[index]] = valueAfter(args, index, allowed)
                index += 2
            } else {
                positional += args[index]
                index++
            }
        }
        return ParsedArguments(positional, options)
    }

    private fun valueAfter(
        args: List<String>,
        index: Int,
        allowed: Set<String>,
    ): String {
        val option = args[index]
        if (option !in allowed) throw CliError("Unknown option: $option")
        return args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
            ?: throw CliError("Missing value after $option")
    }
}
