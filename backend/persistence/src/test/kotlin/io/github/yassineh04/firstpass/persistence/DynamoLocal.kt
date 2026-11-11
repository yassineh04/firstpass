package io.github.yassineh04.firstpass.persistence

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * One DynamoDB Local container for the whole test run, started on first use (Docker must be
 * running). Each test class gets its own table, so tests never see each other's items.
 */
internal object DynamoLocal {
    /** The same image as docker-compose.yml. */
    private const val IMAGE = "amazon/dynamodb-local:3.3.2"
    private const val PORT = 8000

    private class Container : GenericContainer<Container>(DockerImageName.parse(IMAGE))

    private val container: Container by lazy {
        Container()
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(PORT)
            .apply { start() }
    }

    val client: DynamoDbClient by lazy {
        DynamoClients.create(URI.create("http://${container.host}:${container.getMappedPort(PORT)}"))
    }

    /** A new empty table with the production definition. */
    fun freshTable(): FirstPassTable {
        val name = "firstpass-test-${UUID.randomUUID()}"
        TableSetup.createIfMissing(client, name)
        return FirstPassTable(client, name) { error("DynamoDB Local never leaves items unprocessed") }
    }
}

/** The reference time of the tests. */
internal val T0: Instant = Instant.parse("2026-11-09T10:00:00Z")

/** The raw items of a partition, as DynamoDB stores them. */
internal fun FirstPassTable.items(
    pk: String,
    skPrefix: String = "",
): List<Item> = query(pk, skPrefix)
