package io.github.yassineh04.firstpass.eval

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads a text file whatever the editor or shell used to write it: UTF-8, with or without a byte
 * order mark, or UTF-16, which Windows PowerShell 5.1 writes when output is redirected with `>`.
 */
internal object TextFiles {
    private const val BYTE_ORDER_MARK = "﻿"
    private val UTF_16_MARKS = setOf("ÿþ", "þÿ")

    fun read(file: Path): String {
        val bytes = Files.readAllBytes(file)
        val start = String(bytes, 0, minOf(2, bytes.size), Charsets.ISO_8859_1)
        // The UTF-16 decoder reads the byte order from the mark and drops it; the UTF-8 one keeps it.
        val text = if (start in UTF_16_MARKS) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
        return text.removePrefix(BYTE_ORDER_MARK)
    }
}
