package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.finding.Category
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MatcherTest {
    @Test
    fun `matches the same file and category up to 3 lines away`() {
        val near = finding(PATH, 13)
        val far = finding(PATH, 14)

        Matcher.match(listOf(expected(PATH, 10)), listOf(near)).matched shouldContainExactly
            listOf(expected(PATH, 10) to near)
        Matcher.match(listOf(expected(PATH, 10)), listOf(far)).let {
            it.matched.shouldBeEmpty()
            it.missed shouldContainExactly listOf(expected(PATH, 10))
            it.extra shouldContainExactly listOf(far)
        }
    }

    @Test
    fun `never matches another category or another file`() {
        val result =
            Matcher.match(
                listOf(expected(PATH, 10)),
                listOf(finding(PATH, 10, Category.SECURITY), finding("src/Other.kt", 10)),
            )

        result.matched.shouldBeEmpty()
        result.extra.size shouldBe 2
    }

    @Test
    fun `uses each finding once, and prefers the closest one`() {
        val exact = finding(PATH, 10)
        val close = finding(PATH, 12)

        Matcher.match(listOf(expected(PATH, 10), expected(PATH, 11)), listOf(exact)).let {
            it.matched.map { pair -> pair.second } shouldContainExactly listOf(exact)
            it.missed shouldContainExactly listOf(expected(PATH, 11))
        }
        Matcher.match(listOf(expected(PATH, 10)), listOf(close, exact)).let {
            it.matched.map { pair -> pair.second } shouldContainExactly listOf(exact)
            it.extra shouldContainExactly listOf(close)
        }
    }

    @Test
    fun `measures the distance to a multi-line finding from its nearest end`() {
        val range = finding(PATH, line = 9, startLine = 5)

        Matcher.match(listOf(expected(PATH, 7)), listOf(range)).matched.size shouldBe 1
        Matcher.match(listOf(expected(PATH, 12)), listOf(range)).matched.size shouldBe 1
        Matcher.match(listOf(expected(PATH, 13)), listOf(range)).matched.shouldBeEmpty()
    }

    private companion object {
        const val PATH = "src/main/kotlin/Cart.kt"
    }
}
