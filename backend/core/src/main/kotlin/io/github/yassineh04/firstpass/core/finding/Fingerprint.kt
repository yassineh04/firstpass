package io.github.yassineh04.firstpass.core.finding

import java.security.MessageDigest

/**
 * Identifies a finding across commits: SHA-256 of the path, the category, the normalized title
 * and the code of the targeted line without whitespace. The line number is left out on purpose,
 * since it changes whenever lines are added above.
 */
object Fingerprint {
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")

    fun of(
        path: String,
        category: Category,
        title: String,
        lineContent: String,
    ): String {
        val normalizedTitle = title.lowercase().replace(NON_ALPHANUMERIC, " ").trim()
        val code = lineContent.replace(WHITESPACE, "")
        val input = listOf(path, category.wireName, normalizedTitle, code).joinToString("\u0000")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
