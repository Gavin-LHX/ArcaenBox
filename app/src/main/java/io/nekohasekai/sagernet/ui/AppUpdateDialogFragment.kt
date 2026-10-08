package io.nekohasekai.sagernet.ui

import android.app.Application
import android.app.Dialog
import android.content.ClipData
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.update.AppUpdater
import io.nekohasekai.sagernet.update.DownloadedApk
import io.nekohasekai.sagernet.update.GithubRelease
import io.nekohasekai.sagernet.update.UpdateMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed class AppUpdateState {
    object Preparing : AppUpdateState()
    data class Downloading(val percent: Int) : AppUpdateState()
    object Verifying : AppUpdateState()
    data class Ready(val apk: DownloadedApk) : AppUpdateState()
    data class Failed(val message: Int) : AppUpdateState()
}

/** Owns only application context; downloads survive Activity recreation without leaking its window. */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Preparing)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var release: GithubRelease? = null

    fun initialize(value: GithubRelease) {
        if (release != null) return
        release = value
        download()
    }

    fun download() {
        if (job?.isActive == true) return
        val target = release ?: return
        mutableState.value = AppUpdateState.Preparing
        job = viewModelScope.launch {
            try {
                val apk = AppUpdater.download(getApplication(), target) { percent ->
                    mutableState.value = if (percent == 100) AppUpdateState.Verifying else AppUpdateState.Downloading(percent)
                }
                mutableState.value = AppUpdateState.Ready(apk)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = AppUpdateState.Failed(UpdateMessages.resource(e)) }
        }
    }

    suspend fun verifiedApk(): DownloadedApk? {
        val ready = state.value as? AppUpdateState.Ready ?: return null
        mutableState.value = AppUpdateState.Verifying
        return try {
            AppUpdater.verify(getApplication(), ready.apk)
            mutableState.value = ready
            ready.apk
        } catch (e: CancellationException) {
            mutableState.value = ready
            throw e
        } catch (e: Exception) {
            ready.apk.file.delete()
            mutableState.value = AppUpdateState.Failed(UpdateMessages.resource(e))
            null
        }
    }

    fun cancel() { job?.cancel() }
}

class AppUpdateDialogFragment : DialogFragment() {
    private val model: AppUpdateViewModel by viewModels()
    private var messageView: TextView? = null
    private var progressView: ProgressBar? = null
    private var waitingForPermission = false
    private var installAfterPermission = false
    private var externalActivity = false
    private var notice = 0
    private var installing: Job? = null

    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForPermission = false
        externalActivity = false
        if (canInstall()) {
            installAfterPermission = true
            continuePendingInstall()
        } else {
            installAfterPermission = false
            notice = R.string.app_update_permission_denied
            render()
        }
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        externalActivity = false
        // A successful self-update can replace our process. If still here, keep the verified APK for retry.
        notice = R.string.app_update_install_returned
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForPermission = savedInstanceState?.getBoolean("waitingPermission") ?: false
        installAfterPermission = savedInstanceState?.getBoolean("installAfterPermission") ?: false
        externalActivity = savedInstanceState?.getBoolean("externalActivity") ?: false
        notice = savedInstanceState?.getInt("notice") ?: 0
        val release = Gson().fromJson(requireArguments().getString("release"), GithubRelease::class.java)
        model.initialize(release)
        lifecycleScope.launch {
            model.state.collect {
                render()
                continuePendingInstall()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val spacing = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing / 2, spacing, spacing / 2)
        }
        messageView = TextView(context).also { content.addView(it) }
        progressView = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).also {
            it.max = 100
            content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = spacing / 2 })
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.app_update_title)
            .setView(content)
            .setPositiveButton(R.string.app_update_install, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { it.setCanceledOnTouchOutside(false) }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.apply {
            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (model.state.value) {
                    is AppUpdateState.Ready -> requestInstall()
                    is AppUpdateState.Failed -> { notice = 0; model.download() }
                    else -> Unit
                }
            }
            getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                model.cancel()
                installing?.cancel()
                dismiss()
            }
        }
        render()
        continuePendingInstall()
    }

    private fun render() {
        val alert = dialog as? AlertDialog ?: return
        val state = model.state.value
        val busy = state !is AppUpdateState.Ready && state !is AppUpdateState.Failed
        val message = when (state) {
            AppUpdateState.Preparing -> getString(R.string.app_update_preparing)
            is AppUpdateState.Downloading -> getString(R.string.app_update_downloading, state.percent)
            AppUpdateState.Verifying -> getString(R.string.app_update_verifying)
            is AppUpdateState.Ready -> if (notice != 0) getString(notice) else
                getString(R.string.app_update_ready, state.apk.release.tag, state.apk.abi)
            is AppUpdateState.Failed -> getString(state.message)
        }
        messageView?.text = message
        progressView?.apply {
            visibility = if (busy) View.VISIBLE else View.GONE
            isIndeterminate = state !is AppUpdateState.Downloading
            if (state is AppUpdateState.Downloading) progress = state.percent
        }
        alert.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setText(if (state is AppUpdateState.Failed) R.string.app_update_retry else R.string.app_update_install)
            isEnabled = !busy && !externalActivity && installing?.isActive != true
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || requireContext().packageManager.canRequestPackageInstalls()

    private fun requestInstall() {
        if (externalActivity || installing?.isActive == true) return
        notice = 0
        if (!canInstall()) {
            // Open only in response to Install; a denied/back result never loops back into Settings.
            try {
                waitingForPermission = true
                externalActivity = true
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${requireContext().packageName}")))
            } catch (_: Exception) {
                waitingForPermission = false
                externalActivity = false
                notice = R.string.app_update_installer_missing
            }
            render()
        } else launchInstaller()
    }

    private fun continuePendingInstall() {
        if (installAfterPermission && isResumed && model.state.value is AppUpdateState.Ready) {
            installAfterPermission = false
            requestInstall()
        }
    }

    override fun onResume() {
        super.onResume()
        continuePendingInstall()
    }

    private fun launchInstaller() {
        installing = lifecycleScope.launch {
            try {
                val apk = model.verifiedApk() ?: return@launch
                if (!isResumed) {
                    installAfterPermission = true
                    return@launch
                }
                val context = requireContext()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk.file)
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("APK", uri)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
                externalActivity = true
                installer.launch(intent)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                externalActivity = false
                notice = R.string.app_update_installer_missing
            } finally {
                installing = null
                render()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waitingPermission", waitingForPermission)
        outState.putBoolean("installAfterPermission", installAfterPermission)
        outState.putBoolean("externalActivity", externalActivity)
        outState.putInt("notice", notice)
        super.onSaveInstanceState(outState)
    }

    override fun onCancel(dialog: DialogInterface) {
        model.cancel()
        installing?.cancel()
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        messageView = null
        progressView = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "application-update"
        fun newInstance(release: GithubRelease) = AppUpdateDialogFragment().apply {
            arguments = Bundle().apply { putString("release", Gson().toJson(release)) }
        }
    }
}
