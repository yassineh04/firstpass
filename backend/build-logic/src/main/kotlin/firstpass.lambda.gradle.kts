// Convention for the Lambda modules: a .zip package uploaded directly to Lambda, without S3
// (ADR 0011). Classes go at the root and dependencies in lib/, the layout the Java runtime reads.
plugins {
    id("firstpass.kotlin-jvm")
}

// Read on the project here: inside the task's block, the<...>() would look at the task itself.
val mainOutput = the<JavaPluginExtension>().sourceSets.named("main").map { it.output }
val dependencies = configurations.named("runtimeClasspath")

val lambdaZip =
    tasks.register<Zip>("lambdaZip") {
        description = "Packages the function for Lambda: classes at the root, dependency JARs in lib/."
        group = "build"
        archiveFileName = "${project.name}.zip"
        destinationDirectory = layout.buildDirectory.dir("lambda")
        from(mainOutput)
        into("lib") {
            from(dependencies)
        }
        // Same input, same bytes: Terraform only updates a function whose package really changed.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

val checkLambdaSize =
    tasks.register("checkLambdaSize") {
        description = "Fails when the package exceeds the 50 MB Lambda accepts in a direct upload."
        group = "verification"
        val archive = lambdaZip.flatMap { it.archiveFile }
        val limit = 50L * 1024 * 1024
        inputs.file(archive)
        doLast {
            val size = archive.get().asFile.length()
            check(size <= limit) {
                "${archive.get().asFile.name} is ${size / 1024} KB: Lambda accepts at most 50 MB in a direct upload."
            }
            logger.lifecycle("${archive.get().asFile.name}: ${size / 1024} KB of the 50 MB a direct upload allows")
        }
    }

tasks.named("assemble") {
    dependsOn(lambdaZip)
}

tasks.named("check") {
    dependsOn(checkLambdaSize)
}
