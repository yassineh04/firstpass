// Convention for every backend module: Kotlin on JVM 21, strict compilation,
// JUnit, formatting (ktlint), static analysis (detekt) and coverage (Kover).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
}

group = "io.github.yassineh04.firstpass"

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("kotest-assertions-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// The AWS SDK adds the Apache 5 and Netty HTTP clients to every service module. FirstPass uses
// the lighter URLConnection client everywhere (ADR 0014), so neither is ever packaged.
configurations.configureEach {
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

ktlint {
    version = libs.findVersion("ktlint").get().requiredVersion
}

tasks.named("check") {
    dependsOn("koverVerify")
}
