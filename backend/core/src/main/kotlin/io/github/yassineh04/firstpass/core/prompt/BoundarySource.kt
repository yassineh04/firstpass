package io.github.yassineh04.firstpass.core.prompt

import java.security.SecureRandom

/** Draws the random suffix of the markers around untrusted text, one per request. */
fun interface BoundarySource {
    fun next(): String

    companion object {
        private const val LENGTH = 24
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()

        /** Unpredictable values, so that a pull request author cannot write a matching marker in advance. */
        val SECURE: BoundarySource =
            BoundarySource { String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) }
    }
}
