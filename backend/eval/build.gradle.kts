// Offline evaluation harness for review quality.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
}
