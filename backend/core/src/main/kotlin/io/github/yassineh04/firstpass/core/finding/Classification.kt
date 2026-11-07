package io.github.yassineh04.firstpass.core.finding

/** Severity of a finding, declared from the most to the least severe. [wireName] is its JSON value. */
enum class Severity(
    val wireName: String,
) {
    CRITICAL("critical"),
    MAJOR("major"),
    MINOR("minor"),
    NIT("nit"),
}

/** Category of a finding. [wireName] is its JSON value. */
enum class Category(
    val wireName: String,
) {
    BUG("bug"),
    SECURITY("security"),
    PERFORMANCE("performance"),
    RELIABILITY("reliability"),
    TESTS("tests"),
    READABILITY("readability"),
    MAINTAINABILITY("maintainability"),
}
