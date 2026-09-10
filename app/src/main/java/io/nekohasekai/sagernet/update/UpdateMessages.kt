package io.nekohasekai.sagernet.update

import io.nekohasekai.sagernet.R

object UpdateMessages {
    fun resource(error: Exception): Int = when ((error as? UpdateException)?.reason) {
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
