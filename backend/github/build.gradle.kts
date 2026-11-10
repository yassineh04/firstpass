// Adapter: GitHub REST client, GitHub App authentication, webhook payloads.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    // Reads .firstpass.yml with the same tree API as JSON (Apache-2.0; alternative: SnakeYAML Engine directly).
    implementation(libs.jackson.dataformat.yaml)

    testImplementation(libs.wiremock.standalone)
}
