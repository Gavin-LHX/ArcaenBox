package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.Insets
import io.nekohasekai.sagernet.ktx.getColorAttr
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode

        if (!isDialog) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.setBackgroundColor(getColorAttr(R.attr.colorSurface))
            // Older Android versions cannot draw dark system-bar icons.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) window.statusBarColor = Color.BLACK
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) window.navigationBarColor = Color.BLACK
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
            val content = findViewById<android.view.View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.updatePadding(left = bars.left, top = bars.top, right = bars.right,
                    bottom = maxOf(bars.bottom, keyboard.bottom))
                // The root owns the safe area; nested lists must not add it again.
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .build()
            }
            ViewCompat.requestApplyInsets(content)
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
