package io.github.yassineh04.firstpass.core.prompt

/**
 * JSON Schema of the model's answer. It is plain text here, since core uses no JSON library:
 * the llm adapter sends it to providers that support structured output and validates every
 * answer against it before core sees the result.
 */
object ReviewOutputSchema {
    private const val RESOURCE = "review-output.schema.json"

    val text: String by lazy {
        requireNotNull(ReviewOutputSchema::class.java.getResource(RESOURCE)) { "Missing resource $RESOURCE" }.readText()
    }
}
