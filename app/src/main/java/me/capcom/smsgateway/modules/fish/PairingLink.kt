package me.capcom.smsgateway.modules.fish

import android.net.Uri

/**
 * Extrae el código de vinculación de:
 * - https://app.getfish.la/sms/pair/<code> (App Link y contenido del QR)
 * - fishsms://pair/<code> (esquema propio, alternativa si App Links no verificó)
 */
object PairingLink {
    const val HOST = "app.getfish.la"
    const val SCHEME = "fishsms"

    private val CODE_REGEX = Regex("^[A-Za-z0-9]{32}$")

    fun fromUri(uri: Uri?): String? {
        if (uri == null) return null

        val segments = uri.pathSegments ?: return null
        val candidate = when (uri.scheme?.lowercase()) {
            SCHEME -> if (uri.host.equals("pair", ignoreCase = true)) {
                segments.firstOrNull()
            } else {
                null
            }

            "https" -> if (uri.host.equals(HOST, ignoreCase = true)
                && segments.size >= 3
                && segments[0] == "sms"
                && segments[1] == "pair"
            ) {
                segments[2]
            } else {
                null
            }

            else -> null
        }

        return candidate?.takeIf { CODE_REGEX.matches(it) }
    }

    fun fromText(text: String?): String? {
        val value = text?.trim()
        if (value.isNullOrEmpty()) return null

        return fromUri(Uri.parse(value))
    }
}
