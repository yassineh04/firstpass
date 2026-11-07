package io.github.yassineh04.firstpass.llm

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class SchemaDialectsTest {
    private fun commentItems(dialect: SchemaDialect) =
        SchemaDialects.schemaFor(dialect).at("/properties/comments/items")

    private fun JsonNode.strings(): Set<String> = values().map { node -> node.asString() }.toSet()

    @Test
    fun `the full dialect is the schema that core defines`() {
        commentItems(SchemaDialect.FULL).at("/required").strings() shouldBe
            setOf("path", "line", "severity", "category", "title", "body", "confidence")
    }

    @Test
    fun `the strict dialect makes every comment field required and allows null for the optional ones`() {
        val items = commentItems(SchemaDialect.STRICT_ALL_REQUIRED)

        items.at("/required").strings() shouldBe items.at("/properties").propertyNames().toSet()
        items.at("/additionalProperties").asBoolean() shouldBe false
        items.at("/properties/start_line/type").strings() shouldBe setOf("integer", "null")
    }

    @Test
    fun `each call returns its own copy, so callers cannot change the shared schema`() {
        SchemaDialects.schemaFor(SchemaDialect.FULL).put("changed", true)

        SchemaDialects.schemaFor(SchemaDialect.FULL).has("changed") shouldBe false
    }
}
