package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.finding.Severity

internal const val URL = "https://example.org/firstpass"
internal const val MODEL = "gemini-3.5-flash-lite"

internal fun finding(
    path: String = "src/main/kotlin/UserService.kt",
    line: Int = 42,
    severity: Severity = Severity.MAJOR,
    category: Category = Category.BUG,
    title: String = "Crash when the user is missing",
    body: String = "`repo.find(id)` can return null.",
    suggestion: String? = null,
    startLine: Int? = null,
) = Finding(path, startLine, line, severity, category, title, body, suggestion, 0.9, "fp-$path-$line")
