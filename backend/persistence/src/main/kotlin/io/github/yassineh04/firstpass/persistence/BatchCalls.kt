package io.github.yassineh04.firstpass.persistence

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

/** Waits between two attempts; tests replace it so that they never sleep. */
fun interface Pause {
    fun sleep(millis: Long)

    companion object {
        val SLEEP = Pause { Thread.sleep(it) }
    }
}

/** DynamoDB still had unprocessed items after every attempt; the job fails and is replayed later. */
class UnprocessedItemsException(
    attempts: Int,
) : RuntimeException("DynamoDB left items unprocessed after $attempts attempts")

/**
 * BatchWriteItem and BatchGetItem, which accept at most 25 writes and 100 keys per call. DynamoDB
 * returns what it could not process, for example when the provisioned capacity is exceeded: AWS
 * recommends sending it again with an exponential backoff, which [drain] does.
 */
internal class BatchCalls(
    private val client: DynamoDbClient,
    private val table: String,
    private val pause: Pause,
) {
    fun write(requests: List<WriteRequest>) =
        requests.chunked(MAX_WRITES).forEach { chunk ->
            drain(chunk) { batch ->
                client.batchWriteItem { it.requestItems(mapOf(table to batch)) }.unprocessedItems()[table].orEmpty()
            }
        }

    fun get(keys: List<Item>): List<Item> {
        val found = mutableListOf<Item>()
        keys.chunked(MAX_KEYS).forEach { chunk ->
            drain(chunk) { batch ->
                val request =
                    KeysAndAttributes
                        .builder()
                        .keys(batch)
                        .consistentRead(true)
                        .build()
                val response = client.batchGetItem { it.requestItems(mapOf(table to request)) }
                found += response.responses()[table].orEmpty()
                response.unprocessedKeys()[table]?.keys().orEmpty()
            }
        }
        return found
    }

    /** Sends [pending] until nothing is left, pausing twice as long before each new attempt. */
    private fun <T> drain(
        pending: List<T>,
        send: (List<T>) -> List<T>,
    ) {
        var left = pending
        var delay = FIRST_DELAY_MS
        for (attempt in 1..MAX_ATTEMPTS) {
            left = send(left)
            if (left.isEmpty()) return
            if (attempt < MAX_ATTEMPTS) {
                pause.sleep(delay)
                delay *= 2
            }
        }
        throw UnprocessedItemsException(MAX_ATTEMPTS)
    }

    private companion object {
        const val MAX_WRITES = 25
        const val MAX_KEYS = 100
        const val MAX_ATTEMPTS = 6

        /** 0.1 s, then 0.2, 0.4, 0.8 and 1.6 s: about 3 seconds in all. */
        const val FIRST_DELAY_MS = 100L
    }
}
