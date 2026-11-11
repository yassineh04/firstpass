package io.github.yassineh04.firstpass.persistence

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest
import software.amazon.awssdk.services.dynamodb.model.PutRequest
import software.amazon.awssdk.services.dynamodb.model.WriteRequest
import java.time.Instant

/**
 * The single table of FirstPass (design 8) and the operations the stores share. Single-item reads
 * are strongly consistent, so that a job never acts on a state an earlier job has just changed.
 */
class FirstPassTable(
    internal val client: DynamoDbClient,
    val name: String = DEFAULT_NAME,
    pause: Pause = Pause.SLEEP,
) {
    private val batches = BatchCalls(client, name, pause)

    internal fun get(key: Item): Item? =
        client
            .getItem { it.tableName(name).key(key).consistentRead(true) }
            .takeIf { it.hasItem() }
            ?.item()

    internal fun getAll(keys: List<Item>): List<Item> = batches.get(keys)

    internal fun put(item: Item) {
        client.putItem { it.tableName(name).item(item) }
    }

    /** Writes [item] unless one with the same key exists; false when it does. */
    internal fun putIfAbsent(item: Item): Boolean = putUnless(item, "attribute_not_exists(#pk)", emptyMap())

    /** Writes [item] unless one with the same key exists and has not expired at [now]; false when it does. */
    internal fun putIfAbsentOrExpired(
        item: Item,
        now: Instant,
    ): Boolean = putUnless(item, "attribute_not_exists(#pk) OR #expiresAt < :now", mapOf(":now" to Values.expiry(now)))

    internal fun delete(key: Item) {
        client.deleteItem { it.tableName(name).key(key) }
    }

    /**
     * Every item of partition [pk] whose sort key starts with [skPrefix], across all pages. Without
     * a prefix, the whole partition: DynamoDB refuses an empty string in a key condition.
     */
    internal fun query(
        pk: String,
        skPrefix: String = "",
    ): List<Item> {
        val names = mutableMapOf("#pk" to Attr.PK)
        val values = mutableMapOf(":pk" to Values.s(pk))
        var condition = "#pk = :pk"
        if (skPrefix.isNotEmpty()) {
            names["#sk"] = Attr.SK
            values[":prefix"] = Values.s(skPrefix)
            condition += " AND begins_with(#sk, :prefix)"
        }
        return client
            .queryPaginator {
                it
                    .tableName(name)
                    .keyConditionExpression(condition)
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
            }.items()
            .toList()
    }

    /** Writes every item of [items] and deletes every key of [deletions], in this order, 25 per call. */
    internal fun writeAll(
        items: List<Item>,
        deletions: List<Item> = emptyList(),
    ) {
        val puts = items.map { WriteRequest.builder().putRequest(PutRequest.builder().item(it).build()).build() }
        val deletes =
            deletions.map {
                WriteRequest
                    .builder()
                    .deleteRequest(
                        DeleteRequest.builder().key(it).build(),
                    ).build()
            }
        batches.write(puts + deletes)
    }

    /** DynamoDB refuses unused or empty expression names and values, so only the ones used are sent. */
    private fun putUnless(
        item: Item,
        condition: String,
        values: Map<String, AttributeValue>,
    ): Boolean {
        val names = mutableMapOf("#pk" to Attr.PK)
        if (values.isNotEmpty()) names["#expiresAt"] = Attr.EXPIRES_AT
        return try {
            client.putItem { request ->
                request
                    .tableName(name)
                    .item(item)
                    .conditionExpression(condition)
                    .expressionAttributeNames(names)
                if (values.isNotEmpty()) request.expressionAttributeValues(values)
            }
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }
    }

    companion object {
        const val DEFAULT_NAME = "firstpass"
        const val GSI1 = "GSI1"
    }
}

/** The key of a stored item, to delete it. */
internal fun Item.key(): Item = filterKeys { it == Attr.PK || it == Attr.SK }
