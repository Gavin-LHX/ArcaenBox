package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import io.nekohasekai.sagernet.database.DataStore

/** Fallback switch between the redesigned interface and the classic drawer interface. */
object ClassicUi {

    /** Stores the choice and reopens the app in the chosen interface. */
    fun switch(activity: Activity, classic: Boolean) {
        DataStore.useClassicUi = classic
        // MainActivity forwards to LegacyMainActivity while the classic interface is enabled.
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }
}
