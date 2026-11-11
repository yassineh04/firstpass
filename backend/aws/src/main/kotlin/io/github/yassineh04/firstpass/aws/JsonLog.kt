package io.github.yassineh04.firstpass.aws

import tools.jackson.databind.json.JsonMapper
import java.io.PrintStream
import java.time.Clock

/**
 * Logs one JSON object per line on standard output, which CloudWatch Logs keeps and Logs Insights
 * reads field by field (design 11). Callers pass identifiers only: never code, a secret or a token.
 */
class JsonLog(
    private val out: PrintStream = System.out,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun info(
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = write("INFO", message, fields)

    fun error(
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = write("ERROR", message, fields)

    private fun write(
        level: String,
        message: String,
        fields: Map<String, Any?>,
    ) {
        val node =
            JSON
                .createObjectNode()
                .put("timestamp", clock.instant().toString())
                .put("level", level)
                .put("message", message)
        fields.forEach { (name, value) ->
            when (value) {
                null -> Unit
                is Int -> node.put(name, value)
                is Long -> node.put(name, value)
                is Boolean -> node.put(name, value)
                else -> node.put(name, value.toString())
            }
        }
        out.println(JSON.writeValueAsString(node))
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
