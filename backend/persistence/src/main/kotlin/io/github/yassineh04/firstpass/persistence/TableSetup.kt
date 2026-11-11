package io.github.yassineh04.firstpass.persistence

import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

/**
 * Creates the table on DynamoDB Local, for local development and tests. In AWS, Terraform creates
 * it with the same definition (design 6 and 8).
 */
object TableSetup {
    /** Each of the table and GSI1 gets 5 read and 5 write units: 10 and 10 in all, the always-free allowance. */
    private const val UNITS = 5L

    fun createIfMissing(
        client: DynamoDbClient,
        name: String = FirstPassTable.DEFAULT_NAME,
    ) {
        if (exists(client, name)) return
        try {
            client.createTable(definition(name))
        } catch (_: ResourceInUseException) {
            // Created meanwhile by another process: nothing left to do.
            return
        }
        client.waiter().waitUntilTableExists { it.tableName(name) }
        client.updateTimeToLive {
            it.tableName(name).timeToLiveSpecification { ttl -> ttl.attributeName(Attr.EXPIRES_AT).enabled(true) }
        }
    }

    internal fun definition(name: String): CreateTableRequest =
        CreateTableRequest
            .builder()
            .tableName(name)
            .billingMode(BillingMode.PROVISIONED)
            .attributeDefinitions(
                listOf(Attr.PK, Attr.SK, Attr.GSI1_PK, Attr.GSI1_SK).map {
                    AttributeDefinition
                        .builder()
                        .attributeName(it)
                        .attributeType(ScalarAttributeType.S)
                        .build()
                },
            ).keySchema(key(Attr.PK, KeyType.HASH), key(Attr.SK, KeyType.RANGE))
            .provisionedThroughput(capacity())
            .globalSecondaryIndexes(
                GlobalSecondaryIndex
                    .builder()
                    .indexName(FirstPassTable.GSI1)
                    .keySchema(key(Attr.GSI1_PK, KeyType.HASH), key(Attr.GSI1_SK, KeyType.RANGE))
                    .projection { it.projectionType(ProjectionType.ALL) }
                    .provisionedThroughput(capacity())
                    .build(),
            ).build()

    private fun exists(
        client: DynamoDbClient,
        name: String,
    ): Boolean =
        try {
            client.describeTable { it.tableName(name) }
            true
        } catch (_: ResourceNotFoundException) {
            false
        }

    private fun key(
        attribute: String,
        type: KeyType,
    ) = KeySchemaElement
        .builder()
        .attributeName(attribute)
        .keyType(type)
        .build()

    private fun capacity() =
        ProvisionedThroughput
            .builder()
            .readCapacityUnits(UNITS)
            .writeCapacityUnits(UNITS)
            .build()
}
