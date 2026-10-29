package me.capcom.smsgateway.modules.fish

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

/**
 * Datos de la vinculación con Fish que se muestran en la pantalla principal y
 * estado del aviso de versión nueva.
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

    var updateCheckedAt: Long?
        get() = storage.get<Long>(UPDATE_CHECKED_AT)
        set(value) = storage.set(UPDATE_CHECKED_AT, value)

    var latestVersion: String?
        get() = storage.get<String>(LATEST_VERSION)
        set(value) = storage.set(LATEST_VERSION, value)

    fun clear() {
        storage.remove(COMPANY_NAME)
        storage.remove(CONNECTION_ID)
    }

    companion object {
        private const val COMPANY_NAME = "company_name"
        private const val CONNECTION_ID = "connection_id"
        private const val UPDATE_CHECKED_AT = "update_checked_at"
        private const val LATEST_VERSION = "latest_version"
    }
}
