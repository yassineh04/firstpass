// Adapter: the AWS services around the Lambdas (SQS job queue, SSM parameters) and JSON logs.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    // JSON of the jobs and of the logs (Apache-2.0; alternative: kotlinx.serialization, a compiler plugin more).
    implementation(libs.jackson.databind)
    implementation(platform(libs.aws.sdk.bom))
    // SendMessage to the review queue (Apache-2.0; alternative: the SQS HTTP API by hand, with SigV4 signing).
    implementation(libs.aws.sqs)
    // GetParameters for the secrets (Apache-2.0; alternative: the Parameters and Secrets Lambda extension, a layer).
    implementation(libs.aws.ssm)
    implementation(libs.aws.url.connection.client)
}
