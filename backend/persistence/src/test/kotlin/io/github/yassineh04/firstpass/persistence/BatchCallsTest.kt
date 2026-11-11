package io.github.yassineh04.firstpass.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

/** What DynamoDB does under throttling, which DynamoDB Local never does: a client that answers from a script. */
class BatchCallsTest {
    private val pauses = mutableListOf<Long>()

    private class ScriptedClient(
        private val unprocessedWrites: ArrayDeque<Int>,
        private val unprocessedKeys: ArrayDeque<Int> = ArrayDeque(),
    ) : DynamoDbClient {
        val writeSizes = mutableListOf<Int>()
        val getSizes = mutableListOf<Int>()

        override fun serviceName() = "dynamodb"

        override fun close() = Unit

        /** Leaves the last N requests of the batch unprocessed, N taken from the script (0 once it runs out). */
        override fun batchWriteItem(request: BatchWriteItemRequest): BatchWriteItemResponse {
            val batch = request.requestItems().getValue("t")
            writeSizes += batch.size
            val left = batch.takeLast(unprocessedWrites.removeFirstOrNull() ?: 0)
            return BatchWriteItemResponse
                .builder()
                .unprocessedItems(
                    if (left.isEmpty()) {
                        emptyMap()
                    } else {
                        mapOf("t" to left)
                    },
                ).build()
        }

        /** Answers every key it processes with the key itself as the item. */
        override fun batchGetItem(request: BatchGetItemRequest): BatchGetItemResponse {
            val keys = request.requestItems().getValue("t").keys()
            getSizes += keys.size
            val left = keys.takeLast(unprocessedKeys.removeFirstOrNull() ?: 0)
            val unprocessed =
                if (left.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf("t" to KeysAndAttributes.builder().keys(left).build())
                }
            return BatchGetItemResponse
                .builder()
                .responses(mapOf("t" to keys.dropLast(left.size)))
                .unprocessedKeys(unprocessed)
                .build()
        }
    }

    private fun writes(count: Int) =
        List(count) { WriteRequest.builder().deleteRequest { d -> d.key(Keys.of("P#$it", "S")) }.build() }

    @Test
    fun `writes 25 items per call, the most BatchWriteItem accepts`() {
        val client = ScriptedClient(ArrayDeque())

        BatchCalls(client, "t") { pauses += it }.write(writes(60))

        client.writeSizes shouldContainExactly listOf(25, 25, 10)
    }

    @Test
    fun `sends unprocessed items again after a pause that doubles each time`() {
        val client = ScriptedClient(ArrayDeque(listOf(10, 4)))

        BatchCalls(client, "t") { pauses += it }.write(writes(20))

        client.writeSizes shouldContainExactly listOf(20, 10, 4)
        pauses shouldContainExactly listOf(100L, 200L)
    }

    @Test
    fun `gives up after six attempts, so that the job fails and is replayed later`() {
        val client = ScriptedClient(ArrayDeque(List(6) { 1 }))

        shouldThrow<UnprocessedItemsException> { BatchCalls(client, "t") { pauses += it }.write(writes(3)) }

        client.writeSizes.size shouldBe 6
        pauses shouldContainExactly listOf(100L, 200L, 400L, 800L, 1600L)
    }

    @Test
    fun `reads keys in calls of 100, and asks again for the keys left unprocessed`() {
        val client = ScriptedClient(ArrayDeque(), unprocessedKeys = ArrayDeque(listOf(30)))
        val keys = List(150) { Keys.of("P#$it", "S") }

        val found = BatchCalls(client, "t") { pauses += it }.get(keys)

        client.getSizes shouldContainExactly listOf(100, 30, 50)
        found.toSet() shouldBe keys.toSet()
        pauses shouldContainExactly listOf(100L)
    }
}
