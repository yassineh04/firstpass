// Entry point: review worker Lambda (SQS), plain Kotlin.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":github"))
    implementation(project(":llm"))
    implementation(project(":persistence"))
}
