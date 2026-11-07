package io.github.yassineh04.firstpass.core.redaction

/** Replaces secrets in lines of text by `[REDACTED:type]` and tells which types were found. */
internal object LineRedactor {
    data class LineRedaction(
        val content: String,
        val types: Set<SecretType>,
    )

    private class TokenRule(
        val type: SecretType,
        val regex: Regex,
    )

    private val TOKEN_RULES =
        listOf(
            TokenRule(SecretType.AWS_ACCESS_KEY, Regex("""\b(?:AKIA|ASIA)[A-Z0-9]{16}\b""")),
            TokenRule(
                SecretType.GITHUB_TOKEN,
                Regex("""\b(?:gh[pos]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"""),
            ),
            TokenRule(SecretType.SLACK_TOKEN, Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}""")),
            TokenRule(SecretType.JWT, Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""")),
        )

    private val CONNECTION_STRING = Regex("""\b(?<scheme>[a-zA-Z][a-zA-Z0-9+.-]*://)[^\s:/@]+:[^\s/@]+@""")

    private const val SECRET_NAME = """[A-Za-z0-9_.-]*(?:password|passwd|secret|api[_-]?key|token)"""

    /** `password = "value"`, `"api_key": "value"`: a quoted value of at least 12 characters. */
    private val QUOTED_ASSIGNMENT =
        Regex("""(?i)\b(?<name>$SECRET_NAME)(?<sep>["']?\s*[:=]\s*)(?<quote>["'])(?<value>[^"'\s]{12,})\k<quote>""")

    /** `API_KEY=value`: an unquoted value of at least 12 key-like characters, not part of an expression. */
    private val UNQUOTED_ASSIGNMENT =
        Regex("""(?i)\b(?<name>$SECRET_NAME)(?<sep>\s*[:=]\s*)(?<value>[A-Za-z0-9+/=_-]{12,})(?![A-Za-z0-9+/=_.(-])""")

    private val PRIVATE_KEY_BEGIN = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
    private val PRIVATE_KEY_END = Regex("""-----END [A-Z0-9 ]*PRIVATE KEY-----""")

    fun placeholder(type: SecretType): String = "[REDACTED:${type.label}]"

    /**
     * Redacts consecutive lines. A private key block spans several lines: every line from
     * `BEGIN ... PRIVATE KEY` to `END ... PRIVATE KEY` is replaced, and only the first line
     * reports the secret.
     */
    fun redactLines(lines: List<String>): List<LineRedaction> {
        var insideKey = false
        return lines.map { line ->
            val startsKey = !insideKey && PRIVATE_KEY_BEGIN.containsMatchIn(line)
            if (insideKey || startsKey) {
                insideKey = !PRIVATE_KEY_END.containsMatchIn(line)
                LineRedaction(
                    placeholder(SecretType.PRIVATE_KEY),
                    if (startsKey) setOf(SecretType.PRIVATE_KEY) else emptySet(),
                )
            } else {
                redactLine(line)
            }
        }
    }

    private fun redactLine(line: String): LineRedaction {
        val found = mutableSetOf<SecretType>()
        var result = line
        for (rule in TOKEN_RULES) {
            if (rule.regex.containsMatchIn(result)) {
                result = rule.regex.replace(result, placeholder(rule.type))
                found += rule.type
            }
        }
        if (CONNECTION_STRING.containsMatchIn(result)) {
            result =
                CONNECTION_STRING.replace(
                    result,
                ) { "${it.group("scheme")}${placeholder(SecretType.CONNECTION_STRING)}@" }
            found += SecretType.CONNECTION_STRING
        }
        result = redactAssignments(result, found)
        return LineRedaction(result, found)
    }

    private fun redactAssignments(
        line: String,
        found: MutableSet<SecretType>,
    ): String {
        val secret = placeholder(SecretType.ASSIGNED_SECRET)
        val quoted =
            QUOTED_ASSIGNMENT.replace(line) { match ->
                if (isPlaceholder(match.group("value"))) {
                    match.value
                } else {
                    found += SecretType.ASSIGNED_SECRET
                    "${match.group("name")}${match.group("sep")}${match.group("quote")}$secret${match.group("quote")}"
                }
            }
        return UNQUOTED_ASSIGNMENT.replace(quoted) { match ->
            val value = match.group("value")
            if (isPlaceholder(value) || value.none(Char::isDigit)) {
                match.value
            } else {
                found += SecretType.ASSIGNED_SECRET
                "${match.group("name")}${match.group("sep")}$secret"
            }
        }
    }

    /** Template values, already redacted values and filler characters are not secrets. */
    private fun isPlaceholder(value: String): Boolean =
        value.startsWith("\${") ||
            value.startsWith("{{") ||
            value.startsWith("<") ||
            value.startsWith("[REDACTED:") ||
            value.all { it == value.first() }

    private fun MatchResult.group(name: String): String = requireNotNull(groups[name]).value
}
