package io.github.yassineh04.firstpass.core.prompt

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ReviewOutputSchemaTest {
    @Test
    fun `lists every severity and category that core understands`() {
        val schema = ReviewOutputSchema.text

        Severity.entries.forEach { schema shouldContain "\"${it.wireName}\"" }
        Category.entries.forEach { schema shouldContain "\"${it.wireName}\"" }
        schema shouldContain "\"additionalProperties\": false"
    }
}
