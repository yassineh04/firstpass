// Local HTTP server that runs the webhook intake and the review worker together (used with smee).
plugins {
    id("firstpass.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":github"))
    implementation(project(":llm"))
    implementation(project(":persistence"))
    implementation(project(":webhook-handler"))
    implementation(project(":review-worker"))

    testImplementation(testFixtures(project(":persistence")))
    testImplementation(libs.wiremock.standalone)
}

application {
    mainClass = "io.github.yassineh04.firstpass.local.MainKt"
    // The JSON Schema validator logs through SLF4J without a backend: this hides SLF4J's own
    // "no providers" warning, not the messages of FirstPass.
    applicationDefaultJvmArgs = listOf("-Dslf4j.internal.verbosity=ERROR")
}

// The .env file and the private key path are read from the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
}
