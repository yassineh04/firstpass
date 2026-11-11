package io.github.yassineh04.firstpass.persistence

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI
import java.util.UUID

/**
 * One DynamoDB Local container for the whole test run of a module, started on first use (Docker
 * must be running). Each test gets its own table, so tests never see each other's items.
 */
object DynamoLocal {
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

    /** The address of the container, as DYNAMODB_ENDPOINT would give it. */
    val endpoint: URI by lazy { URI.create("http://${container.host}:${container.getMappedPort(PORT)}") }

    val client: DynamoDbClient by lazy { DynamoClients.create(endpoint) }

    /** A table name no other test uses. */
    fun uniqueName(): String = "firstpass-test-${UUID.randomUUID()}"

    /** A new empty table with the production definition. */
    fun freshTable(): FirstPassTable =
        FirstPassTable(client, uniqueName()) { error("DynamoDB Local never leaves items unprocessed") }
            .also { it.createIfMissing() }
}
