package com.k1llerwhale.sonicsight.util

/**
 * Pure classification half of MainActivity.describeError (seam S6,
 * docs/SEAMS.md): the substring rules are extracted verbatim so MU-403 can
 * pin them on the JVM; the Activity maps the kind to localized copy.
 */
enum class StreamErrorKind {
    /** Model checkpoint not loaded / unknown model id on the server. */
    MODEL_UNAVAILABLE,

    /** Server down, wrong address, network fault, or deadline expiry. */
    SERVER_UNREACHABLE,

    /** Anything else — surfaced with the raw message. */
    GENERIC,
}

fun classifyStreamError(raw: String?): StreamErrorKind {
    val msg = raw ?: ""
    return when {
        msg.contains("not loaded", ignoreCase = true) ||
            msg.contains("Unknown model", ignoreCase = true) ->
            StreamErrorKind.MODEL_UNAVAILABLE
        msg.contains("UNAVAILABLE", ignoreCase = true) ||
            msg.contains("io exception", ignoreCase = true) ||
            msg.contains("deadline", ignoreCase = true) ->
            StreamErrorKind.SERVER_UNREACHABLE
        else -> StreamErrorKind.GENERIC
    }
}
