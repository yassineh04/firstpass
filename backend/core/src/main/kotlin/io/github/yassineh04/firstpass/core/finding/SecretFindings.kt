package io.github.yassineh04.firstpass.core.finding

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.redaction.DetectedSecret
import io.github.yassineh04.firstpass.core.redaction.SecretType
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage

/**
 * Turns the secrets found by the redactor into critical security findings, written by core
 * itself (not by the model) in the review language. The text never repeats the secret value.
 */
object SecretFindings {
    fun of(
        secrets: List<DetectedSecret>,
        files: List<AnnotatedFile>,
        language: ReviewLanguage,
    ): List<Finding> {
        val filesByPath = files.associateBy { it.file.path }
        val texts = TEXTS.getValue(language)
        return secrets.map { secret ->
            Finding(
                path = secret.path,
                startLine = null,
                line = secret.line,
                severity = Severity.CRITICAL,
                category = Category.SECURITY,
                title = texts.title,
                body = texts.body.replace("{secret}", texts.names.getValue(secret.type)),
                suggestion = null,
                confidence = 1.0,
                fingerprint =
                    Fingerprint.of(
                        secret.path,
                        Category.SECURITY,
                        texts.title,
                        filesByPath[secret.path]?.contentAt(secret.line).orEmpty(),
                    ),
            )
        }
    }

    private class Texts(
        val title: String,
        val body: String,
        val names: Map<SecretType, String>,
    )

    private val TEXTS =
        mapOf(
            ReviewLanguage.EN to
                Texts(
                    title = "Possible secret in the code",
                    body =
                        "This line seems to contain {secret}. Remove it from the code and from the git history, " +
                            "then revoke it: anyone with access to the repository may already have it.",
                    names =
                        mapOf(
                            SecretType.AWS_ACCESS_KEY to "an AWS access key",
                            SecretType.PRIVATE_KEY to "a private key",
                            SecretType.GITHUB_TOKEN to "a GitHub token",
                            SecretType.SLACK_TOKEN to "a Slack token",
                            SecretType.JWT to "a JSON Web Token",
                            SecretType.CONNECTION_STRING to "a connection string with credentials",
                            SecretType.ASSIGNED_SECRET to "a password or an API key",
                        ),
                ),
            ReviewLanguage.FR to
                Texts(
                    title = "Secret possible dans le code",
                    body =
                        "Cette ligne semble contenir {secret}. Retire-la du code et de l'historique git, " +
                            "puis révoque-la : toute personne ayant accès au dépôt a pu la récupérer.",
                    names =
                        mapOf(
                            SecretType.AWS_ACCESS_KEY to "une clé d'accès AWS",
                            SecretType.PRIVATE_KEY to "une clé privée",
                            SecretType.GITHUB_TOKEN to "un jeton GitHub",
                            SecretType.SLACK_TOKEN to "un jeton Slack",
                            SecretType.JWT to "un jeton JWT",
                            SecretType.CONNECTION_STRING to "une chaîne de connexion avec des identifiants",
                            SecretType.ASSIGNED_SECRET to "un mot de passe ou une clé d'API",
                        ),
                ),
        )
}
