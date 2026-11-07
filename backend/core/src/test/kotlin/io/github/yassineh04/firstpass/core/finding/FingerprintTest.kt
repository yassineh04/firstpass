package io.github.yassineh04.firstpass.core.finding

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class FingerprintTest {
    private val reference =
        Fingerprint.of(
            "src/A.kt",
            Category.BUG,
            "Crash when the user is missing",
            "val user = repo.find(id)!!",
        )

    @Test
    fun `ignores whitespace in the code and case or punctuation in the title`() {
        Fingerprint.of(
            "src/A.kt",
            Category.BUG,
            "  crash, when the USER is missing!",
            "  val user =repo.find( id )!!  ",
        ) shouldBe
            reference
    }

    @Test
    fun `changes with the path, the category, the title words or the code`() {
        Fingerprint.of(
            "src/B.kt",
            Category.BUG,
            "Crash when the user is missing",
            "val user = repo.find(id)!!",
        ) shouldNotBe
            reference
        Fingerprint.of(
            "src/A.kt",
            Category.RELIABILITY,
            "Crash when the user is missing",
            "val user = repo.find(id)!!",
        ) shouldNotBe
            reference
        Fingerprint.of(
            "src/A.kt",
            Category.BUG,
            "Crash when the id is missing",
            "val user = repo.find(id)!!",
        ) shouldNotBe
            reference
        Fingerprint.of(
            "src/A.kt",
            Category.BUG,
            "Crash when the user is missing",
            "val user = repo.get(id)!!",
        ) shouldNotBe
            reference
    }

    @Test
    fun `is a SHA-256 in hexadecimal`() {
        reference.matches(Regex("[0-9a-f]{64}")) shouldBe true
    }
}
