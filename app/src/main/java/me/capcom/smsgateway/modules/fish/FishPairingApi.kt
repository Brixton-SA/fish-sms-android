package me.capcom.smsgateway.modules.fish

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import me.capcom.smsgateway.BuildConfig

/**
 * Cliente de los endpoints públicos de vinculación de Fish
 * (ver contrato "Fish SMS — vinculación por QR").
 */
class FishPairingApi(
    private val baseUrl: String = BASE_URL,
) {
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

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

    suspend fun claim(code: String): ClaimResponse {
        return post(PATH_CLAIM, ClaimRequest(code), ClaimResponse::class.java)
    }

    suspend fun complete(request: CompleteRequest): CompleteResponse {
        return post(PATH_COMPLETE, request, CompleteResponse::class.java)
    }

    private suspend fun <T> post(path: String, body: Any, type: Class<T>): T {
        val response: HttpResponse = try {
            client.post(baseUrl + path) {
                setBody(TextContent(gson.toJson(body), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FishPairingException(FishPairingException.Reason.Network, e)
        }

        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

        val status = response.status.value
        if (status in 200..299) {
            return try {
                gson.fromJson(text, type)
                    ?: throw FishPairingException(FishPairingException.Reason.Server)
            } catch (e: JsonParseException) {
                throw FishPairingException(FishPairingException.Reason.Server, e)
            }
        }

        val error = try {
            gson.fromJson(text, ErrorResponse::class.java)?.error
        } catch (e: JsonParseException) {
            null
        }

        throw FishPairingException(reasonFor(status, error))
    }

    private fun reasonFor(status: Int, error: String?): FishPairingException.Reason {
        when (error) {
            "invalid_code" -> return FishPairingException.Reason.InvalidCode
            "expired" -> return FishPairingException.Reason.Expired
            "already_used" -> return FishPairingException.Reason.AlreadyUsed
            "rate_limited" -> return FishPairingException.Reason.RateLimited
            "credentials_rejected" -> return FishPairingException.Reason.CredentialsRejected
            "channel_limit" -> return FishPairingException.Reason.ChannelLimit
            "server_unavailable" -> return FishPairingException.Reason.ServerUnavailable
            "unavailable" -> return FishPairingException.Reason.Unavailable
        }

        return when (status) {
            403 -> FishPairingException.Reason.ChannelLimit
            404 -> FishPairingException.Reason.InvalidCode
            410 -> FishPairingException.Reason.Expired
            422 -> FishPairingException.Reason.CredentialsRejected
            429 -> FishPairingException.Reason.RateLimited
            502 -> FishPairingException.Reason.ServerUnavailable
            503 -> FishPairingException.Reason.Unavailable
            else -> FishPairingException.Reason.Server
        }
    }

    data class ClaimRequest(
        val code: String,
    )

    data class ClaimResponse(
        val serverUrl: String?,
        val privateToken: String?,
        val companyName: String?,
    )

    data class CompleteRequest(
        val code: String,
        val login: String,
        val password: String,
        val deviceId: String,
        val deviceName: String,
        val phoneNumber: String?,
        val simCount: Int,
        val signingSecret: String?,
    )

    data class CompleteResponse(
        val ok: Boolean?,
        val connectionId: Long?,
        val companyName: String?,
    )

    data class ErrorResponse(
        val error: String?,
    )

    companion object {
        const val BASE_URL = "https://app.getfish.la"

        private const val PATH_CLAIM = "/api/public/sms-pairing/claim"
        private const val PATH_COMPLETE = "/api/public/sms-pairing/complete"
    }
}
