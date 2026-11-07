// Entry point: dashboard API (Spring Boot is added in phase 4).
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":github"))
    implementation(project(":persistence"))
}
