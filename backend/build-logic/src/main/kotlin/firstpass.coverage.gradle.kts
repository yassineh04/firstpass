// Convention for the root project: merged coverage report of all modules,
// with the global minimum of 70 % line coverage (core has its own 85 % rule).
plugins {
    base
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        verify {
            rule {
                minBound(70)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
