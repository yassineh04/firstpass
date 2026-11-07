package io.github.yassineh04.firstpass.core.finding

/**
 * Cleans text written by the model before it is published on GitHub: mentions cannot notify
 * anyone, images and dangerous HTML are removed, and the length is bounded. Only a list of
 * risky tags is removed, so that generics such as `Map<String, Int>` in an explanation survive.
 */
internal object TextSanitizer {
    private const val ELLIPSIS = "…"

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT_OR_STYLE = Regex("""<(script|style)\b[^>]*>.*?</\1\s*>""", OPTIONS)
    private val HTML_COMMENT = Regex("""<!--.*?-->""", OPTIONS)
    private val MARKDOWN_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val UNSAFE_TAG_NAMES =
        listOf(
            "a",
            "img",
            "iframe",
            "object",
            "embed",
            "svg",
            "video",
            "audio",
            "picture",
            "source",
            "form",
            "input",
            "button",
            "details",
            "summary",
            "link",
            "meta",
        )
    private val UNSAFE_TAG = Regex("""</?(?:${UNSAFE_TAG_NAMES.joinToString("|")})\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** `@user` or `@org/team`, unless part of an e-mail address or already inside backticks. */
    private val MENTION = Regex("""(?<![\w`/@])@[A-Za-z0-9][A-Za-z0-9-]*(?:/[A-Za-z0-9_.-]*[A-Za-z0-9_-])?""")
    private val REPEATED_SPACES = Regex("""[ \t]{2,}""")

    fun clean(
        text: String,
        maxLength: Int,
    ): String {
        val withoutMarkup =
            listOf(SCRIPT_OR_STYLE, HTML_COMMENT, MARKDOWN_IMAGE, UNSAFE_TAG).fold(text) { current, pattern ->
                pattern.replace(current, "")
            }
        val cleaned =
            MENTION
                .replace(withoutMarkup) { "`${it.value}`" }
                .replace(REPEATED_SPACES, " ")
                .trim()
        return if (cleaned.length <= maxLength) cleaned else cleaned.take(maxLength - ELLIPSIS.length) + ELLIPSIS
    }
}
