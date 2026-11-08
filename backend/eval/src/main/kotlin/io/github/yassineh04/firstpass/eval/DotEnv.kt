package io.github.yassineh04.firstpass.eval

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the local `.env` file, made of `KEY=value` lines as in `.env.example`. Comments, blank
 * lines and malformed lines are ignored. The values are never printed.
 */
internal object DotEnv {
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val QUOTES = setOf('"', '\'')

    fun load(file: Path): Map<String, String> =
        if (Files.isRegularFile(file)) parse(TextFiles.read(file).lines()) else emptyMap()

    fun parse(lines: List<String>): Map<String, String> =
        lines
            .mapNotNull { raw ->
                val line =
                    raw
                        .removePrefix("﻿")
                        .trim()
                        .removePrefix("export ")
                val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                if (line.startsWith('#') || !KEY.matches(key)) {
                    null
                } else {
                    key to unquote(line.substringAfter('=').trim())
                }
            }.toMap()

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() in QUOTES && value.last() == value.first()) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
