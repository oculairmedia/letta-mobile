package com.letta.mobile.data.repository

import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport

fun ISettingsRepository.activeBackendIsIroh(): Boolean {
    return IrohChannelTransport.shouldUseIroh(this.activeConfig.value?.serverUrl)
}
