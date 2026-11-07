// Root project: merges the coverage of every module and checks the global threshold.
plugins {
    id("firstpass.coverage")
}

dependencies {
    kover(project(":core"))
    kover(project(":github"))
    kover(project(":llm"))
    kover(project(":persistence"))
    kover(project(":webhook-handler"))
    kover(project(":review-worker"))
    kover(project(":api"))
    kover(project(":eval"))
    kover(project(":local-dev"))
}
