// Domain module (hexagonal core): no AWS, no Spring, no HTTP client.
plugins {
    id("firstpass.kotlin-jvm")
}

dependencies {
    testImplementation(libs.archunit)
}

kover {
    reports {
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
