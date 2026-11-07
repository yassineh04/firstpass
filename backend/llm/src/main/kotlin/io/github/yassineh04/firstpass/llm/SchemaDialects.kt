package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.prompt.ReviewOutputSchema
import tools.jackson.databind.node.ObjectNode

/** How a provider wants the output schema. Answers are always validated against the full schema. */
enum class SchemaDialect {
    /** The schema of core as is (Gemini). */
    FULL,

    /** Every comment field required, optional ones nullable (Groq strict mode requires it). */
    STRICT_ALL_REQUIRED,
}

object SchemaDialects {
    private val fullSchema: ObjectNode = JSON.readTree(ReviewOutputSchema.text) as ObjectNode

    /** Returns a fresh copy, so that callers can add provider-specific fields safely. */
    fun schemaFor(dialect: SchemaDialect): ObjectNode {
        val schema = fullSchema.deepCopy()
        if (dialect == SchemaDialect.STRICT_ALL_REQUIRED) {
            val items = schema.at("/properties/comments/items") as ObjectNode
            val required = items.putArray("required")
            items.path("properties").propertyNames().forEach { required.add(it) }
        }
        return schema
    }
}
