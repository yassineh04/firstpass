// Adapter: DynamoDB repositories.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.aws.sdk.bom))
    // The low-level DynamoDB client (Apache-2.0; alternative: the Enhanced Client, see ADR 0014).
    implementation(libs.aws.dynamodb) {
        // The SDK adds two HTTP clients by default; the lighter URLConnection client replaces them.
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // Loads faster than the Apache client, which suits Lambda (Apache-2.0; alternative: the CRT client).
    implementation(libs.aws.url.connection.client)
    // A real DynamoDB Local in Docker for the tests (MIT; alternative: docker compose started by hand).
    testImplementation(libs.testcontainers)
}
