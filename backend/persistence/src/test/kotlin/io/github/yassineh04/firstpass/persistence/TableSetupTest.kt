package io.github.yassineh04.firstpass.persistence

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import java.util.UUID

class TableSetupTest {
    @Test
    fun `defines one table and GSI1 with 10 read and 10 write units in all, never on demand`() {
        val definition = TableSetup.definition("firstpass")
        val index = definition.globalSecondaryIndexes().single()

        definition.billingMode() shouldBe BillingMode.PROVISIONED
        val reads =
            definition.provisionedThroughput().readCapacityUnits() + index.provisionedThroughput().readCapacityUnits()
        val writes =
            definition.provisionedThroughput().writeCapacityUnits() + index.provisionedThroughput().writeCapacityUnits()
        reads shouldBe 10
        writes shouldBe 10
    }

    @Test
    fun `creates the table of the design on DynamoDB Local, once, with TTL on expiresAt`() {
        val name = "firstpass-setup-${UUID.randomUUID()}"
        val client = DynamoLocal.client

        TableSetup.createIfMissing(client, name)
        TableSetup.createIfMissing(client, name)

        val table = client.describeTable { it.tableName(name) }.table()
        table.keySchema().map { it.attributeName() to it.keyType() } shouldContainExactly
            listOf("pk" to KeyType.HASH, "sk" to KeyType.RANGE)
        val index = table.globalSecondaryIndexes().single()
        index.indexName() shouldBe "GSI1"
        index.keySchema().map { it.attributeName() } shouldContainExactly listOf("gsi1pk", "gsi1sk")
        index.projection().projectionType() shouldBe ProjectionType.ALL
        client.describeTimeToLive { it.tableName(name) }.timeToLiveDescription().attributeName() shouldBe "expiresAt"
    }
}
