package io.github.yassineh04.firstpass.eval

/** A problem the user can fix (bad argument, missing file or key); shown without a stack trace. */
internal class CliError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
