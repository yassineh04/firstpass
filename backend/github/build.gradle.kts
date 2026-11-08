// Adapter: GitHub REST client, GitHub App authentication, webhook payloads.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.wiremock.standalone)
}
