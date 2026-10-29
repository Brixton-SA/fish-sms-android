package me.capcom.smsgateway.modules.fish

import me.capcom.smsgateway.modules.settings.PreferencesStorage
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val fishModule = module {
    factory {
        FishSettings(
            PreferencesStorage(get(), "fish")
        )
    }
    singleOf(::FishPairingService)
}
