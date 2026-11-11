// Entry point: webhook Lambda (function URL), plain Kotlin.
plugins {
    id("firstpass.lambda")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":github"))
    implementation(project(":persistence"))
    implementation(project(":aws"))
    implementation(libs.aws.lambda.java.core)
    // Reads the function URL event (Apache-2.0; alternative: aws-lambda-java-events, a larger library).
    implementation(libs.jackson.databind)
}
