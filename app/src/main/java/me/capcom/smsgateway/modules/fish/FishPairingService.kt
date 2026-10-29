package me.capcom.smsgateway.modules.fish

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.modules.localserver.domain.SimCard
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.modules.webhooks.WebhooksSettings

/**
 * Vincula este dispositivo con una empresa de Fish a partir del código del QR:
 * claim -> configurar servidor -> registrar dispositivo -> activar Cloud server, SSE y
 * recepción de SMS -> complete.
 *
 * Vive como singleton para que una rotación de pantalla o un cambio de Activity no corte
 * la vinculación en curso; la UI observa [state].
 */
class FishPairingService(
    private val context: Context,
    private val gatewaySettings: GatewaySettings,
    private val gatewayService: GatewayService,
    private val localServerSettings: LocalServerSettings,
    private val orchestratorService: OrchestratorService,
    private val settingsHelper: SettingsHelper,
    private val webhooksSettings: WebhooksSettings,
    private val fishSettings: FishSettings,
) {
    sealed class State {
        object Idle : State()
        class Working(@StringRes val messageRes: Int) : State()

        /** [canRetry]: se puede reintentar solo el último paso con el mismo código. */
        class Failed(
            val reason: FishPairingException.Reason,
            val canRetry: Boolean = false,
        ) : State()

        class Done(val companyName: String) : State()
    }

    private val api = FishPairingApi()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Se apaga ANTES de publicar el estado final, para que la UI pueda volver a Idle o
    // reintentar apenas recibe Done/Failed aunque la corrutina todavía no haya terminado.
    @Volatile
    private var running = false

    // Ya se cambió la configuración del gateway: si la vinculación falla hay que deshacerla.
    @Volatile
    private var touchedSettings = false

    // Código y empresa pendientes de un "complete" reintentable (502 server_unavailable).
    @Volatile
    private var pendingComplete: PendingComplete? = null

    private class PendingComplete(val code: String, val claimCompanyName: String?)

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State>
        get() = _state

    val isPaired: Boolean
        get() = fishSettings.companyName != null && gatewaySettings.registrationInfo != null

    val companyName: String?
        get() = fishSettings.companyName

    val isWorking: Boolean
        get() = running

    /**
     * Arranca la vinculación con [code]. Llamar desde el hilo principal.
     * Devuelve false si ya hay una vinculación en curso.
     */
    fun pair(code: String): Boolean {
        if (isWorking) return false

        launchPairing(R.string.fish_pairing_step_claim) {
            // Un código nuevo descarta cualquier reintento pendiente del anterior.
            if (pendingComplete != null) {
                pendingComplete = null
                rollback()
            }
            runPairing(code)
        }

        return true
    }

    /**
     * Reintenta solo el "complete" con el mismo código, sin volver a registrar el
     * dispositivo. Disponible cuando el estado es Failed(canRetry = true).
     */
    fun retry(): Boolean {
        if (isWorking) return false
        val pending = pendingComplete ?: return false

        launchPairing(R.string.fish_pairing_step_complete) {
            runComplete(pending.code, pending.claimCompanyName)
        }

        return true
    }

    /** Informa un error detectado en la UI (QR ajeno, permisos denegados). */
    fun fail(reason: FishPairingException.Reason) {
        if (isWorking) return
        _state.value = State.Failed(reason)
    }

    /**
     * Vuelve al estado de reposo después de un error o de mostrar el resultado.
     * Si quedaba un reintento pendiente, se descarta y se deshace la configuración.
     */
    fun reset() {
        if (isWorking) return

        if (pendingComplete != null) {
            pendingComplete = null
            scope.launch { rollback() }
        }
        _state.value = State.Idle
    }

    /** Reactiva los servicios si el dispositivo está vinculado. Hilo principal. */
    fun restartServices() {
        if (!isPaired) return

        orchestratorService.start(context, false)
        SSEForegroundService.start(context)
    }

    /** Desvincula este dispositivo y apaga todos los servicios. Hilo principal. */
    fun unpair() {
        if (isWorking) return

        pendingComplete = null
        touchedSettings = false
        orchestratorService.stop(context)
        gatewaySettings.registrationInfo = null
        gatewaySettings.enabled = false
        settingsHelper.autostart = false
        fishSettings.clear()

        _state.value = State.Idle
    }

    private fun launchPairing(@StringRes firstStep: Int, block: suspend () -> String) {
        running = true
        _state.value = State.Working(firstStep)
        scope.launch {
            var finalState: State = State.Failed(FishPairingException.Reason.Network)
            try {
                finalState = State.Done(block())
            } catch (e: FishPairingException) {
                if (e.reason == FishPairingException.Reason.ServerUnavailable
                    && pendingComplete != null
                ) {
                    // Reintentable: se conserva el registro y los servicios activos.
                    finalState = State.Failed(e.reason, canRetry = true)
                } else {
                    pendingComplete = null
                    rollback()
                    finalState = State.Failed(e.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingComplete = null
                rollback()
                finalState = State.Failed(FishPairingException.Reason.Server)
            } finally {
                // Nunca dejar la pantalla en "Conectando…" para siempre.
                running = false
                _state.postValue(finalState)
            }
        }
    }

    private suspend fun runPairing(code: String): String {
        val claim = api.claim(code)
        val serverUrl = claim.serverUrl?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("https://") }
            ?: throw FishPairingException(FishPairingException.Reason.Server)
        val privateToken = claim.privateToken?.takeIf { it.isNotBlank() }
            ?: throw FishPairingException(FishPairingException.Reason.Server)

        _state.postValue(State.Working(R.string.fish_pairing_step_register))

        // Arranque limpio: se descarta cualquier vinculación o registro anterior para que el
        // servidor de SMS entregue credenciales nuevas (login/password) a Fish.
        touchedSettings = true
        withContext(Dispatchers.Main) {
            orchestratorService.stop(context)
        }
        fishSettings.clear()
        gatewaySettings.registrationInfo = null
        gatewaySettings.configureServer(serverUrl, privateToken)
        gatewaySettings.enabled = true
        localServerSettings.enabled = false

        try {
            gatewayService.registerDevice(
                context,
                null,
                GatewayService.RegistrationMode.Anonymous
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            throw FishPairingException(FishPairingException.Reason.Network, e)
        } catch (e: Exception) {
            throw FishPairingException(FishPairingException.Reason.Registration, e)
        }

        _state.postValue(State.Working(R.string.fish_pairing_step_start))

        settingsHelper.autostart = true
        withContext(Dispatchers.Main) {
            orchestratorService.start(context, false)
            SSEForegroundService.start(context)
        }

        _state.postValue(State.Working(R.string.fish_pairing_step_complete))

        return runComplete(code, claim.companyName)
    }

    /**
     * "complete" NO es idempotente: tras un 200 la repetición devuelve 410 already_used.
     * Por eso solo se reintenta ante 502 server_unavailable (Fish no llegó a crear el
     * canal) y nunca ante un corte de red, en el que la respuesta pudo haberse perdido.
     */
    private suspend fun runComplete(code: String, claimCompanyName: String?): String {
        pendingComplete = null

        val info = gatewaySettings.registrationInfo
            ?: throw FishPairingException(FishPairingException.Reason.Registration)
        val password = info.password
            ?: throw FishPairingException(FishPairingException.Reason.Registration)

        val simCards = SubscriptionsHelper.getActiveSimCards(context)
        val response = try {
            api.complete(
                FishPairingApi.CompleteRequest(
                    code = code,
                    login = info.login,
                    password = password,
                    // Tal cual lo devuelve el registro: Fish lo busca en /3rdparty/v1/devices.
                    deviceId = info.id,
                    deviceName = deviceName(),
                    phoneNumber = phoneNumber(simCards),
                    simCount = simCards.size,
                    signingSecret = webhooksSettings.signingKey,
                )
            )
        } catch (e: FishPairingException) {
            when (e.reason) {
                FishPairingException.Reason.ServerUnavailable -> {
                    pendingComplete = PendingComplete(code, claimCompanyName)
                    throw e
                }

                FishPairingException.Reason.Network ->
                    throw FishPairingException(FishPairingException.Reason.CompleteInterrupted, e)

                else -> throw e
            }
        }

        val companyName = response.companyName?.takeIf { it.isNotBlank() }
            ?: claimCompanyName?.takeIf { it.isNotBlank() }
            ?: ""

        touchedSettings = false
        fishSettings.companyName = companyName
        fishSettings.connectionId = response.connectionId?.toString()

        return companyName
    }

    private suspend fun rollback() {
        if (!touchedSettings) return
        touchedSettings = false

        try {
            withContext(Dispatchers.Main) {
                orchestratorService.stop(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Seguir limpiando aunque algún servicio no se haya podido detener.
        }

        gatewaySettings.registrationInfo = null
        gatewaySettings.enabled = false
        settingsHelper.autostart = false
        fishSettings.clear()
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()

        val name = when {
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }

        return name.ifEmpty { "Android" }
    }

    /** Número del primer chip que lo informe, en E.164; null si ningún chip lo informa. */
    private fun phoneNumber(simCards: List<SimCard>): String? {
        val raw = simCards
            .sortedBy { it.slotIndex }
            .firstNotNullOfOrNull { it.phoneNumber?.takeIf { number -> number.isNotBlank() } }
            ?: return null

        val region = (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.simCountryIso
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?: DEFAULT_REGION

        return try {
            val util = PhoneNumberUtil.getInstance()
            val number = util.parse(raw, region)
            if (util.isValidNumber(number)) {
                util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                null
            }
        } catch (e: NumberParseException) {
            null
        }
    }

    companion object {
        private const val DEFAULT_REGION = "PY"
    }
}
