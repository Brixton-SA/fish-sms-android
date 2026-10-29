package me.capcom.smsgateway.modules.fish

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

/**
 * Datos de la vinculación con Fish que se muestran en la pantalla principal.
 */
class FishSettings(
    private val storage: KeyValueStorage,
) {
    var companyName: String?
        get() = storage.get<String>(COMPANY_NAME)
        set(value) = storage.set(COMPANY_NAME, value)

    var connectionId: String?
        get() = storage.get<String>(CONNECTION_ID)
        set(value) = storage.set(CONNECTION_ID, value)

    fun clear() {
        storage.remove(COMPANY_NAME)
        storage.remove(CONNECTION_ID)
    }

    companion object {
        private const val COMPANY_NAME = "company_name"
        private const val CONNECTION_ID = "connection_id"
    }
}
