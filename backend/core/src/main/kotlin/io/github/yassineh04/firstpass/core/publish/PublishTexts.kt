package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.filter.SkipReason
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import java.util.Locale

/** A noun or adjective in its singular and plural forms. */
internal class Forms(
    val one: String,
    val many: String,
) {
    fun of(count: Int): String = if (count == 1) one else many
}

/**
 * Every text FirstPass publishes, in one language. One class for both languages, so that a text
 * missing in one of them is a compile error, not a blank in a review.
 */
internal class PublishTexts(
    val locale: Locale,
    /** ":" in English, " :" in French, where typography puts a space before the colon. */
    val colon: String,
    val severity: Map<Severity, String>,
    val severityCount: Map<Severity, Forms>,
    val category: Map<Category, String>,
    val skipReason: Map<SkipReason, String>,
    val failure: Map<LlmFailure, String>,
    val issue: Forms,
    val file: Forms,
    val reviewTitle: String,
    val noIssues: String,
    /** "Found 3 issues: 1 major, 2 minor." from the count, its text and the breakdown. */
    val found: (Int, String, String) -> String,
    val overview: String,
    val others: String,
    val othersExplained: (String) -> String,
    val files: String,
    val reviewed: String,
    val skipped: String,
    val notReviewed: String,
    val partly: String,
    val truncated: String,
    val failed: String,
    val more: (String) -> String,
    val configTitle: String,
    val configIntro: String,
    val invalidFile: String,
    val unsupportedVersion: String,
    val unknownSetting: (String) -> String,
    val invalidValue: (String, String) -> String,
    val tooLongInstructions: (String) -> String,
    val oneOf: (String) -> String,
    val listOf: (String) -> String,
    val wholeNumber: (String, String) -> String,
    val trueOrFalse: String,
    val text: String,
    val patterns: String,
    val aiName: String,
    val commentsOnly: String,
    val reactions: String,
    val howItWorks: String,
    val tooLarge: (String, String) -> String,
    val inProgressTitle: String,
    val inProgressSummary: String,
    val noIssuesTitle: String,
    val noIssuesSummary: String,
    /** Check run title with issues, from the count and its text: "Found 3 issues". */
    val foundTitle: (Int, String) -> String,
    val foundSummary: (String) -> String,
    val unavailableTitle: String,
    val unavailableSummary: String,
    val outdatedTitle: String,
    val outdatedSummary: String,
) {
    companion object {
        fun of(language: ReviewLanguage): PublishTexts =
            when (language) {
                ReviewLanguage.EN -> ENGLISH
                ReviewLanguage.FR -> FRENCH
            }

        private val ENGLISH =
            PublishTexts(
                locale = Locale.US,
                colon = ":",
                severity =
                    mapOf(
                        Severity.CRITICAL to "Critical",
                        Severity.MAJOR to "Major",
                        Severity.MINOR to "Minor",
                        Severity.NIT to "Nit",
                    ),
                severityCount =
                    mapOf(
                        Severity.CRITICAL to Forms("critical", "critical"),
                        Severity.MAJOR to Forms("major", "major"),
                        Severity.MINOR to Forms("minor", "minor"),
                        Severity.NIT to Forms("nit", "nits"),
                    ),
                category =
                    mapOf(
                        Category.BUG to "Bug",
                        Category.SECURITY to "Security",
                        Category.PERFORMANCE to "Performance",
                        Category.RELIABILITY to "Reliability",
                        Category.TESTS to "Tests",
                        Category.READABILITY to "Readability",
                        Category.MAINTAINABILITY to "Maintainability",
                    ),
                skipReason =
                    mapOf(
                        SkipReason.DELETED to "deleted",
                        SkipReason.NO_PATCH to "no diff available",
                        SkipReason.SENSITIVE to "may contain secrets",
                        SkipReason.LOCKFILE to "lockfile",
                        SkipReason.BUILD_OUTPUT to "build output",
                        SkipReason.MINIFIED to "minified",
                        SkipReason.BINARY to "binary",
                        SkipReason.EXCLUDED_BY_CONFIG to "excluded by the settings",
                        SkipReason.NOT_INCLUDED to "outside the include patterns",
                        SkipReason.DOCUMENTATION to "documentation",
                    ),
                failure =
                    mapOf(
                        LlmFailure.RATE_LIMITED to "rate limit reached",
                        LlmFailure.UNAVAILABLE to "provider unavailable",
                        LlmFailure.INVALID_OUTPUT to "invalid answer from the model",
                        LlmFailure.REJECTED to "request rejected by the provider",
                    ),
                issue = Forms("issue", "issues"),
                file = Forms("file", "files"),
                reviewTitle = "FirstPass review",
                noIssues = "No issues found in the changed lines.",
                found = { _, count, breakdown -> "Found $count: $breakdown." },
                overview = "Overview",
                others = "Other findings",
                othersExplained = { limit ->
                    "Not posted as inline comments: beyond the limit of $limit comments, or on lines outside the diff."
                },
                files = "Files",
                reviewed = "Reviewed",
                skipped = "Skipped",
                notReviewed = "Not reviewed (no batch left for them)",
                partly = "partly",
                truncated = "Cut to fit a batch (the end of the hunk was not reviewed)",
                failed = "No valid answer",
                more = { count -> "and $count more" },
                configTitle = "Settings file",
                configIntro = "Some settings in `.firstpass.yml` were not applied:",
                invalidFile = "the file could not be read, so the default settings apply",
                unsupportedVersion = "`version`: only version 1 is supported, so the whole file was ignored",
                unknownSetting = { key -> "$key is not a FirstPass setting and was ignored" },
                invalidValue = { key, expected -> "$key was ignored: expected $expected" },
                tooLongInstructions = { limit ->
                    "`instructions`: only the first $limit characters are sent to the model"
                },
                oneOf = { values -> "one of $values" },
                listOf = { values -> "a non-empty list of $values" },
                wholeNumber = { from, to -> "a whole number from $from to $to" },
                trueOrFalse = "`true` or `false`",
                text = "text",
                patterns = "glob patterns, as a list or a single one",
                aiName = "FirstPass (AI)",
                commentsOnly = "FirstPass only comments: it never approves or blocks a pull request.",
                reactions = "Useful? React with 👍 or 👎",
                howItWorks = "How FirstPass works",
                tooLarge = { changed, limit ->
                    "FirstPass did not review this pull request: it changes $changed lines, " +
                        "more than the limit of $limit. " +
                        "Split it into smaller pull requests, or raise `max_changed_lines` in `.firstpass.yml`."
                },
                inProgressTitle = "Review in progress",
                inProgressSummary = "FirstPass is reviewing the changed lines.",
                noIssuesTitle = "No issues found",
                noIssuesSummary = "FirstPass found no issues in the changed lines.",
                foundTitle = { _, count -> "Found $count" },
                foundSummary = { breakdown -> "$breakdown. See the review comments on the pull request." },
                unavailableTitle = "Review unavailable",
                unavailableSummary =
                    "No AI provider gave a valid answer, so this push was not reviewed. " +
                        "FirstPass will review the next push.",
                outdatedTitle = "Review skipped",
                outdatedSummary = "A newer push arrived during the review: FirstPass reviews that push instead.",
            )

        private val FRENCH =
            PublishTexts(
                locale = Locale.FRANCE,
                colon = " :",
                severity =
                    mapOf(
                        Severity.CRITICAL to "Critique",
                        Severity.MAJOR to "Majeur",
                        Severity.MINOR to "Mineur",
                        Severity.NIT to "Détail",
                    ),
                severityCount =
                    mapOf(
                        Severity.CRITICAL to Forms("critique", "critiques"),
                        Severity.MAJOR to Forms("majeur", "majeurs"),
                        Severity.MINOR to Forms("mineur", "mineurs"),
                        Severity.NIT to Forms("détail", "détails"),
                    ),
                category =
                    mapOf(
                        Category.BUG to "Bug",
                        Category.SECURITY to "Sécurité",
                        Category.PERFORMANCE to "Performance",
                        Category.RELIABILITY to "Fiabilité",
                        Category.TESTS to "Tests",
                        Category.READABILITY to "Lisibilité",
                        Category.MAINTAINABILITY to "Maintenabilité",
                    ),
                skipReason =
                    mapOf(
                        SkipReason.DELETED to "supprimé",
                        SkipReason.NO_PATCH to "aucun diff disponible",
                        SkipReason.SENSITIVE to "peut contenir des secrets",
                        SkipReason.LOCKFILE to "fichier de verrouillage",
                        SkipReason.BUILD_OUTPUT to "fichier généré par le build",
                        SkipReason.MINIFIED to "minifié",
                        SkipReason.BINARY to "binaire",
                        SkipReason.EXCLUDED_BY_CONFIG to "exclu par les réglages",
                        SkipReason.NOT_INCLUDED to "hors des motifs include",
                        SkipReason.DOCUMENTATION to "documentation",
                    ),
                failure =
                    mapOf(
                        LlmFailure.RATE_LIMITED to "limite de débit atteinte",
                        LlmFailure.UNAVAILABLE to "fournisseur indisponible",
                        LlmFailure.INVALID_OUTPUT to "réponse invalide du modèle",
                        LlmFailure.REJECTED to "requête refusée par le fournisseur",
                    ),
                issue = Forms("problème", "problèmes"),
                file = Forms("fichier", "fichiers"),
                reviewTitle = "Revue FirstPass",
                noIssues = "Aucun problème trouvé dans les lignes modifiées.",
                found = {
                    number,
                    count,
                    breakdown,
                    ->
                    "$count ${if (number == 1) "trouvé" else "trouvés"} : $breakdown."
                },
                overview = "Vue d'ensemble",
                others = "Autres problèmes",
                othersExplained = { limit ->
                    "Non publiés en commentaires inline : au-delà de la limite de $limit commentaires, " +
                        "ou sur des lignes hors du diff."
                },
                files = "Fichiers",
                reviewed = "Relus",
                skipped = "Ignorés",
                notReviewed = "Non relus (plus de lot disponible)",
                partly = "en partie",
                truncated = "Coupés pour tenir dans un lot (la fin du hunk n'a pas été relue)",
                failed = "Sans réponse valide",
                more = { count -> "et $count autres" },
                configTitle = "Fichier de réglages",
                configIntro = "Certains réglages de `.firstpass.yml` n'ont pas été appliqués :",
                invalidFile = "le fichier n'a pas pu être lu, les réglages par défaut s'appliquent",
                unsupportedVersion = "`version` : seule la version 1 est prise en charge, tout le fichier a été ignoré",
                unknownSetting = { key -> "$key n'est pas un réglage FirstPass et a été ignoré" },
                invalidValue = { key, expected -> "$key a été ignoré : valeur attendue, $expected" },
                tooLongInstructions = { limit ->
                    "`instructions` : seuls les $limit premiers caractères sont envoyés au modèle"
                },
                oneOf = { values -> "une valeur parmi $values" },
                listOf = { values -> "une liste non vide de $values" },
                wholeNumber = { from, to -> "un nombre entier de $from à $to" },
                trueOrFalse = "`true` ou `false`",
                text = "du texte",
                patterns = "des motifs glob, en liste ou seuls",
                aiName = "FirstPass (IA)",
                commentsOnly =
                    "FirstPass se contente de commenter : il n'approuve ni ne bloque jamais une pull request.",
                reactions = "Utile ? Réagissez avec 👍 ou 👎",
                howItWorks = "Fonctionnement de FirstPass",
                tooLarge = { changed, limit ->
                    "FirstPass n'a pas relu cette pull request : elle modifie $changed lignes, " +
                        "au-delà de la limite de $limit. " +
                        "Découpez-la en pull requests plus petites, ou relevez `max_changed_lines` dans `.firstpass.yml`."
                },
                inProgressTitle = "Revue en cours",
                inProgressSummary = "FirstPass relit les lignes modifiées.",
                noIssuesTitle = "Aucun problème trouvé",
                noIssuesSummary = "FirstPass n'a trouvé aucun problème dans les lignes modifiées.",
                foundTitle = { number, count -> "$count ${if (number == 1) "trouvé" else "trouvés"}" },
                foundSummary = { breakdown -> "$breakdown. Voir les commentaires de revue sur la pull request." },
                unavailableTitle = "Revue indisponible",
                unavailableSummary =
                    "Aucun fournisseur d'IA n'a donné de réponse valide : ce push n'a pas été relu. " +
                        "FirstPass relira le prochain push.",
                outdatedTitle = "Revue abandonnée",
                outdatedSummary =
                    "Un push plus récent est arrivé pendant la revue : FirstPass relit ce push à la place.",
            )
    }
}
