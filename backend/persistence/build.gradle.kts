// Adapter: DynamoDB repositories.
plugins {
    id("firstpass.kotlin-jvm")
    // Shares the DynamoDB Local container with the tests of other modules (local-dev).
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.aws.sdk.bom))
    // The low-level DynamoDB client (Apache-2.0; alternative: the Enhanced Client, see ADR 0014).
    // Its default HTTP clients are excluded for every module, in the firstpass.kotlin-jvm convention.
    implementation(libs.aws.dynamodb)
    // Loads faster than the Apache client, which suits Lambda (Apache-2.0; alternative: the CRT client).
    implementation(libs.aws.url.connection.client)
    // A real DynamoDB Local in Docker for the tests (MIT; alternative: docker compose started by hand).
    testFixturesImplementation(libs.testcontainers)
    // The fixtures hand out a DynamoDbClient, so their users see the SDK too.
    testFixturesApi(platform(libs.aws.sdk.bom))
    testFixturesApi(libs.aws.dynamodb)
}
