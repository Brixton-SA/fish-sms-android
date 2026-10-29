package me.capcom.smsgateway.modules.fish

class FishPairingException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        /** 404 invalid_code o enlace con formato inválido. */
        InvalidCode,

        /** 410 expired. */
        Expired,

        /** 410 already_used. */
        AlreadyUsed,

        /** 429 rate_limited. */
        RateLimited,

        /** claim 503 unavailable: la vinculación por QR está deshabilitada por ahora. */
        Unavailable,

        /** complete 403 channel_limit: el plan no admite más canales. */
        ChannelLimit,

        /** complete 502 server_unavailable: Fish no llegó al servidor de SMS. Reintentable. */
        ServerUnavailable,

        /**
         * Se cortó la red durante complete: Fish pudo haber creado el canal y dado el código
         * por usado, así que no se reintenta con el mismo código.
         */
        CompleteInterrupted,

        /** 422 credentials_rejected. */
        CredentialsRejected,

        /** El servidor de SMS no aceptó el registro del dispositivo. */
        Registration,

        /** Sin conexión o tiempo de espera agotado. */
        Network,

        /** Respuesta inesperada de Fish. */
        Server,

        /** El QR escaneado no es un QR de vinculación de Fish. */
        InvalidQr,

        /** El usuario no otorgó los permisos de SMS. */
        PermissionsDenied,
    }
}
