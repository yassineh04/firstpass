package io.github.yassineh04.firstpass.local

import java.nio.file.Files
import java.nio.file.Path

/**
 * The local settings: the `.env` file at the repository root, then the environment variables,
 * which win. `.env` holds `KEY=value` lines as in `.env.example`; comments, blank lines and
 * malformed lines are ignored. The values are never printed.
 */
internal object LocalEnvironment {
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private const val BOM = '\uFEFF'

    fun load(
        dotEnv: Path,
        environment: Map<String, String> = System.getenv(),
    ): Map<String, String> =
        parse(if (Files.isRegularFile(dotEnv)) Files.readAllLines(dotEnv) else emptyList()) + environment

    fun parse(lines: List<String>): Map<String, String> =
        lines
            .map { it.trimStart(BOM).trim().removePrefix("export ") }
            .filter { !it.startsWith('#') && KEY.matches(it.substringBefore('=', missingDelimiterValue = "").trim()) }
            .associate { it.substringBefore('=').trim() to unquote(it.substringAfter('=').trim()) }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() in "\"'" && value.last() == value.first()) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
