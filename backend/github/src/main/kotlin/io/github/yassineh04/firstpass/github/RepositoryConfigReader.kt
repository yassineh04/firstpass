package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.settings.ParsedConfig
import io.github.yassineh04.firstpass.core.settings.RepositoryConfig
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper

/**
 * Reads `.firstpass.yml` into the generic tree that core checks setting by setting. YAML 1.2
 * rules apply (SnakeYAML Engine): `yes` and `on` are text, not booleans.
 */
internal object RepositoryConfigReader {
    private val YAML = YAMLMapper()
    private const val MAX_DETAIL = 200

    fun read(text: String): ParsedConfig =
        try {
            RepositoryConfig.parse(YAML.readTree(text).toTree())
        } catch (error: JacksonException) {
            RepositoryConfig.unreadable(error.message.orEmpty().take(MAX_DETAIL))
        }

    /** Maps with string keys in file order, lists, text, booleans, numbers and nulls. */
    private fun JsonNode.toTree(): Any? =
        when {
            isObject -> properties().associate { (key, value) -> key to value.toTree() }
            isArray -> values().map { it.toTree() }
            isString -> asString()
            isBoolean -> booleanValue()
            isIntegralNumber -> if (canConvertToLong()) longValue() else asString()
            isFloatingPointNumber -> doubleValue()
            else -> null
        }
}
