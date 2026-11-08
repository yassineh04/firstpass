// Local review of a patch file, and the offline evaluation harness for review quality.
plugins {
    id("firstpass.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
}

application {
    mainClass = "io.github.yassineh04.firstpass.eval.MainKt"
    // The JSON Schema validator logs through SLF4J, and no logging backend is needed here:
    // this hides SLF4J's own "no providers" warning, not the messages of the tool.
    applicationDefaultJvmArgs = listOf("-Dslf4j.internal.verbosity=ERROR")
}

// Paths on the command line, and the .env file, are read from the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
}
