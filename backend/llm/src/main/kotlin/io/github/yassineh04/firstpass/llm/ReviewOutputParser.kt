package io.github.yassineh04.firstpass.llm

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.ModelComment
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.prompt.ReviewOutputSchema
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode

sealed interface ParsedOutput {
    data class Valid(
        val review: ModelReview,
    ) : ParsedOutput

    /** [errors] name the failing locations and rules; they never quote the code under review. */
    data class Invalid(
        val errors: List<String>,
    ) : ParsedOutput
}

/**
 * Reads the model's answer and checks it against the full output schema of core, whatever
 * the provider enforced on its side (some providers ignore length limits). Only an answer that
 * passes every rule is turned into typed comments.
 */
class ReviewOutputParser {
    private val schema: Schema =
        SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(ReviewOutputSchema.text, InputFormat.JSON)

    fun parse(content: String): ParsedOutput {
        val node = readJson(content) ?: return ParsedOutput.Invalid(listOf("The answer is not valid JSON."))
        val errors = schema.validate(node)
        return if (errors.isEmpty()) {
            ParsedOutput.Valid(toReview(node))
        } else {
            ParsedOutput.Invalid(errors.take(MAX_ERRORS).map { it.toString() })
        }
    }

    private fun readJson(content: String): JsonNode? =
        try {
            JSON.readTree(withoutCodeFence(content))
        } catch (_: JacksonException) {
            null
        }

    private fun toReview(node: JsonNode): ModelReview =
        ModelReview(
            summary = node.path("summary").asString(),
            comments = node.path("comments").values().map(::toComment),
        )

    private fun toComment(node: JsonNode): ModelComment =
        ModelComment(
            path = node.path("path").asString(),
            startLine = node.path("start_line").orNull()?.asInt(),
            line = node.path("line").asInt(),
            severity = Severity.entries.first { it.wireName == node.path("severity").asString() },
            category = Category.entries.first { it.wireName == node.path("category").asString() },
            title = node.path("title").asString(),
            body = node.path("body").asString(),
            suggestion = node.path("suggestion").orNull()?.asString(),
            confidence = node.path("confidence").asDouble(),
        )

    private fun JsonNode.orNull(): JsonNode? = takeUnless { it.isNull || it.isMissingNode }

    private companion object {
        const val MAX_ERRORS = 10

        /** Models often wrap JSON in a Markdown block, even when told not to. */
        val CODE_FENCE = Regex("""^```[A-Za-z]*\s*\n(.*)\n\s*```$""", RegexOption.DOT_MATCHES_ALL)

        fun withoutCodeFence(content: String): String {
            val trimmed = content.trim()
            return CODE_FENCE.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
        }
    }
}
