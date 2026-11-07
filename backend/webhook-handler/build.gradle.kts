// Entry point: webhook Lambda (function URL), plain Kotlin.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":github"))
    implementation(project(":persistence"))
}
