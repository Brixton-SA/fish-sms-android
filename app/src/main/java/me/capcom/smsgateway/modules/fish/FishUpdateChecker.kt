package me.capcom.smsgateway.modules.fish

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.BuildConfig

/**
 * Avisa cuando hay una versión de Fish SMS más nueva que la instalada.
 *
 * Consulta el último release de GitHub como mucho una vez por día y guarda la versión
 * encontrada, así el aviso sigue visible entre aperturas sin volver a consultar.
 * Si la red falla no muestra nada.
 */
class FishUpdateChecker(
    private val settings: FishSettings,
) {
    private val gson = Gson()

    private val client = HttpClient(OkHttp) {
        install(UserAgent) {
            agent = "FishSMS/" + BuildConfig.VERSION_NAME
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            requestTimeoutMillis = 30_000L
            socketTimeoutMillis = 30_000L
        }
        expectSuccess = false
    }

    /** Versión más nueva disponible, o null si la instalada está al día. */
    suspend fun availableUpdate(): String? {
        val now = System.currentTimeMillis()
        val lastCheck = settings.updateCheckedAt ?: 0L
        if (now - lastCheck !in 0 until CHECK_INTERVAL_MS) {
            fetchLatestVersion()?.let { settings.latestVersion = it }
            settings.updateCheckedAt = now
        }

        val latest = settings.latestVersion ?: return null
        return latest.takeIf { isNewer(it, BuildConfig.VERSION_NAME) }
    }

    private suspend fun fetchLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(LATEST_RELEASE_URL) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (response.status.value !in 200..299) return@withContext null

            gson.fromJson(response.bodyAsText(), Release::class.java)
                ?.tagName
                ?.let { parseVersion(it) }
                ?.joinToString(".")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private data class Release(
        @SerializedName("tag_name")
        val tagName: String?,
    )

    companion object {
        const val DOWNLOAD_URL = "https://app.getfish.la/sms/download"

        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Brixton-SA/fish-sms-android/releases/latest"
        private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** "v2.0.1" o "2.0.1" -> [2, 0, 1]; null si no es una versión X.Y.Z. */
        fun parseVersion(value: String): List<Int>? {
            val parts = value.trim().removePrefix("v").split(".")
            if (parts.size != 3) return null
            return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        }

        fun isNewer(candidate: String, current: String): Boolean {
            val a = parseVersion(candidate) ?: return false
            val b = parseVersion(current) ?: return false
            for (i in a.indices) {
                if (a[i] != b[i]) return a[i] > b[i]
            }
            return false
        }
    }
}
