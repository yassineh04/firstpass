// Local HTTP server that calls the handlers directly (used with smee).
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":webhook-handler"))
    implementation(project(":review-worker"))
    implementation(project(":api"))
}
