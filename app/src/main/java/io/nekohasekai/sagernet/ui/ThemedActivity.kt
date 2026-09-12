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
import androidx.core.graphics.ColorUtils
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
    // MainActivity lets the bottom bar and drawer paint the navigation safe area.
    protected open val drawBehindBottomNavigationBar = false

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
            val lightSurface = ColorUtils.calculateLuminance(getColorAttr(R.attr.colorSurface)) > 0.5
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
            if (drawBehindBottomNavigationBar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val content = findViewById<android.view.View>(android.R.id.content)
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
                val bottomOwnedByChildren = drawBehindBottomNavigationBar && keyboard.bottom <= bars.bottom
                view.updatePadding(left = bars.left, top = bars.top, right = bars.right,
                    bottom = if (bottomOwnedByChildren) 0 else maxOf(bars.bottom, keyboard.bottom))
                // Only MainActivity passes the bottom safe area to its bar and drawer.
                // Other activities keep the existing content/keyboard inset ownership.
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(),
                        if (bottomOwnedByChildren) Insets.of(0, 0, 0, bars.bottom) else Insets.NONE)
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
