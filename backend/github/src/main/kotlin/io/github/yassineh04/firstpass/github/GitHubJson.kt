package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** The JSON mapper of the github adapter, with Kotlin support. Thread-safe once built. */
internal val JSON: JsonMapper = jsonMapper { addModule(kotlinModule()) }

/** A payload or a response without a field FirstPass needs. */
internal class MissingField(
    path: String,
) : RuntimeException("Missing or invalid field: $path")

/** The node at a dotted [path], such as `pull_request.head.sha`. */
internal fun JsonNode.dig(path: String): JsonNode = path.split('.').fold(this) { node, name -> node.path(name) }

internal fun JsonNode.text(path: String): String =
    dig(path).takeIf { it.isString }?.asString() ?: throw MissingField(path)

internal fun JsonNode.textOrNull(path: String): String? = dig(path).takeIf { it.isString }?.asString()

internal fun JsonNode.long(path: String): Long =
    dig(path).takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: throw MissingField(path)

internal fun JsonNode.int(path: String): Int = long(path).toInt()

/** A missing boolean counts as false. */
internal fun JsonNode.flag(path: String): Boolean = dig(path).takeIf { it.isBoolean }?.booleanValue() ?: false

/** A repository object of a payload or of the REST API. */
internal fun repositoryOf(node: JsonNode): RepositoryRef =
    RepositoryRef(node.long("id"), node.text("full_name"), node.flag("private"))
