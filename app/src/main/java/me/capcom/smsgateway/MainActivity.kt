package me.capcom.smsgateway

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import me.capcom.smsgateway.databinding.ActivityMainBinding
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.modules.fish.FishPairingException
import me.capcom.smsgateway.modules.fish.FishPairingService
import me.capcom.smsgateway.modules.fish.FishUpdateChecker
import me.capcom.smsgateway.modules.fish.PairingLink
import me.capcom.smsgateway.modules.gateway.GatewayService
import org.koin.android.ext.android.inject

/**
 * Pantalla principal de Fish SMS.
 *
 * Sin vincular: "Escanear QR". Vinculado: "Conectado a <empresa>", estado del servicio y
 * "Desvincular". También recibe los enlaces de vinculación
 * (https://app.getfish.la/sms/pair/<code> y fishsms://pair/<code>).
 * La configuración del proyecto original queda en el menú "Opciones avanzadas".
 * Arriba de todo avisa cuando hay una versión nueva para descargar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pairingService: FishPairingService by inject()
    private val gatewayService: GatewayService by inject()
    private val updateChecker: FishUpdateChecker by inject()

    /** Código recibido que espera la respuesta del pedido de permisos. */
    private var pendingCode: String? = null

    private var serviceActive = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        pendingCode = savedInstanceState?.getString(STATE_PENDING_CODE)

        binding.buttonScan.setOnClickListener { startScan() }
        binding.buttonRetry.setOnClickListener {
            val state = pairingService.state.value
            if (state is FishPairingService.State.Failed && state.canRetry) {
                pairingService.retry()
            } else {
                pairingService.reset()
                startScan()
            }
        }
        binding.buttonRescanSecondary.setOnClickListener {
            pairingService.reset()
            startScan()
        }
        binding.buttonOpenSettings.setOnClickListener { openAppSettings() }
        binding.buttonUnpair.setOnClickListener { confirmUnpair() }
        binding.buttonBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.buttonReactivate.setOnClickListener { pairingService.restartServices() }
        binding.buttonUpdate.setOnClickListener { openDownload() }

        pairingService.state.observe(this) { render() }
        gatewayService.isActiveLiveData(this).observe(this) { active ->
            serviceActive = active
            renderServiceStatus()
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_CODE, pendingCode)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_advanced) {
            startActivity(AdvancedActivity.starter(this, AdvancedActivity.TAB_INDEX_HOME))
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    //region Vinculación
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return

        val code = PairingLink.fromUri(data)
        if (code == null) {
            pairingService.fail(FishPairingException.Reason.InvalidCode)
            return
        }

        onCodeReceived(code)
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        // contents == null: el usuario cerró el lector sin escanear.
        val contents = result.contents ?: return@registerForActivityResult

        val code = PairingLink.fromText(contents)
        if (code == null) {
            pairingService.fail(FishPairingException.Reason.InvalidQr)
            return@registerForActivityResult
        }

        onCodeReceived(code)
    }

    private fun startScan() {
        if (pairingService.isWorking) return

        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt(getString(R.string.fish_scan_prompt))
        options.setBeepEnabled(false)
        options.setOrientationLocked(false)

        scanLauncher.launch(options)
    }

    private fun onCodeReceived(code: String) {
        if (pairingService.isWorking) return

        pendingCode = code

        val missing = missingPermissions()
        if (missing.isEmpty()) {
            startPairing()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.fish_permissions_title)
            .setMessage(R.string.fish_permissions_message)
            .setCancelable(false)
            .setPositiveButton(R.string.fish_continue) { _, _ ->
                permissionsRequest.launch(missing.toTypedArray())
            }
            .setNegativeButton(R.string.fish_cancel) { _, _ ->
                pendingCode = null
            }
            .show()
    }

    private val permissionsRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(Manifest.permission.SEND_SMS)
            && hasPermission(Manifest.permission.RECEIVE_SMS)
        ) {
            startPairing()
        } else {
            pendingCode = null
            pairingService.fail(FishPairingException.Reason.PermissionsDenied)
        }
    }

    private fun startPairing() {
        val code = pendingCode ?: return
        pendingCode = null

        pairingService.pair(code)
    }

    private fun requiredPermissions(): List<String> = listOfNotNull(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.READ_PHONE_STATE,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Manifest.permission.READ_PHONE_NUMBERS
        } else {
            null
        },
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        },
    )

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter { !hasPermission(it) }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    //endregion

    //region Render
    private fun render() {
        when (val state = pairingService.state.value ?: FishPairingService.State.Idle) {
            is FishPairingService.State.Working -> {
                showSection(Section.Working)
                binding.textWorking.setText(state.messageRes)
            }

            is FishPairingService.State.Failed -> {
                showSection(Section.Error)
                binding.textErrorMessage.setText(errorMessage(state.reason))
                binding.buttonRetry.setText(
                    if (state.canRetry) R.string.fish_retry else R.string.fish_rescan
                )
                binding.buttonRescanSecondary.isVisible = state.canRetry
                binding.buttonOpenSettings.isVisible =
                    state.reason == FishPairingException.Reason.PermissionsDenied
            }

            is FishPairingService.State.Done -> {
                // reset() vuelve a Idle y el observer redibuja la pantalla de "Conectado".
                pairingService.reset()
                requestIgnoreBatteryOptimizations()
            }

            FishPairingService.State.Idle -> {
                if (pairingService.isPaired) {
                    showPaired()
                } else {
                    showSection(Section.Unpaired)
                }
            }
        }
    }

    private fun showPaired() {
        showSection(Section.Paired)

        val company = pairingService.companyName
        binding.textConnectedCompany.text = if (company.isNullOrBlank()) {
            getString(R.string.fish_connected_generic)
        } else {
            getString(R.string.fish_connected_to, company)
        }

        binding.layoutBattery.isVisible = !isIgnoringBatteryOptimizations()
        renderServiceStatus()
    }

    private fun renderServiceStatus() {
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (serviceActive) R.color.fish_green else R.color.red_800
            )
        )
        binding.textServiceStatus.setText(
            if (serviceActive) R.string.fish_service_active else R.string.fish_service_inactive
        )
        binding.buttonReactivate.isVisible = !serviceActive && pairingService.isPaired
    }

    private fun showSection(section: Section) {
        binding.layoutUnpaired.isVisible = section == Section.Unpaired
        binding.layoutWorking.isVisible = section == Section.Working
        binding.layoutError.isVisible = section == Section.Error
        binding.layoutPaired.isVisible = section == Section.Paired
    }

    private fun errorMessage(reason: FishPairingException.Reason): Int = when (reason) {
        FishPairingException.Reason.InvalidCode -> R.string.fish_error_invalid_code
        FishPairingException.Reason.Expired -> R.string.fish_error_expired
        FishPairingException.Reason.AlreadyUsed -> R.string.fish_error_already_used
        FishPairingException.Reason.RateLimited -> R.string.fish_error_rate_limited
        FishPairingException.Reason.Unavailable -> R.string.fish_error_unavailable
        FishPairingException.Reason.ChannelLimit -> R.string.fish_error_channel_limit
        FishPairingException.Reason.ServerUnavailable -> R.string.fish_error_server_unavailable
        FishPairingException.Reason.CompleteInterrupted -> R.string.fish_error_complete_interrupted
        FishPairingException.Reason.CredentialsRejected -> R.string.fish_error_credentials
        FishPairingException.Reason.Registration -> R.string.fish_error_registration
        FishPairingException.Reason.Network -> R.string.fish_error_network
        FishPairingException.Reason.Server -> R.string.fish_error_server
        FishPairingException.Reason.InvalidQr -> R.string.fish_error_invalid_qr
        FishPairingException.Reason.PermissionsDenied -> R.string.fish_error_permissions
    }

    private enum class Section { Unpaired, Working, Error, Paired }
    //endregion

    //region Acciones
    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.fish_unpair_title)
            .setMessage(R.string.fish_unpair_message)
            .setPositiveButton(R.string.fish_unpair) { _, _ ->
                pairingService.unpair()
                Toast.makeText(this, R.string.fish_unpaired, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.fish_cancel, null)
            .show()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations()) return

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // El fabricante no expone la pantalla; la tarjeta sigue visible.
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            binding.layoutUpdate.isVisible = updateChecker.availableUpdate() != null
        }
    }

    private fun openDownload() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FishUpdateChecker.DOWNLOAD_URL)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
    //endregion

    companion object {
        private const val STATE_PENDING_CODE = "pendingCode"

        fun starter(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }
    }
}
