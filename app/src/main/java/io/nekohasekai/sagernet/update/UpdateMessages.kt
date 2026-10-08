package io.nekohasekai.sagernet.update

import io.nekohasekai.sagernet.R

object UpdateMessages {
    fun resource(error: Exception): Int = when ((error as? UpdateException)?.reason) {
        "app_abi" -> R.string.app_update_abi
        "app_invalid" -> R.string.app_update_invalid
        "app_checksum_missing" -> R.string.app_update_checksum_missing
        "app_checksum" -> R.string.app_update_checksum
        "app_package" -> R.string.app_update_package
        "app_version" -> R.string.app_update_version
        "app_signature" -> R.string.app_update_signature
        "app_storage" -> R.string.app_update_storage
        "component_unavailable" -> R.string.kernel_unavailable
        "missing" -> R.string.update_missing
        "rate_limit" -> R.string.update_rate_limit
        "invalid" -> R.string.update_invalid
        "signature" -> R.string.update_signature
        "checksum" -> R.string.update_checksum
        "incompatible" -> R.string.update_incompatible
        "storage" -> R.string.update_storage
        "stop" -> R.string.update_stop_failed
        else -> R.string.update_network
    }
}
