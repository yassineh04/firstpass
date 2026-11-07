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
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
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
