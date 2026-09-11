package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.ResourceFiles
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Date
import java.util.concurrent.TimeUnit

class AssetsActivity : ThemedActivity() {
    private lateinit var layout: LayoutAssetsBinding
    private val adapter = AssetAdapter()
    private var busy = false
    private val assetNames = listOf("geoip.db", "geosite.db")
    private val providers = listOf(
        listOf("SagerNet/sing-geoip", "SagerNet/sing-geosite"),
        listOf("soffchen/sing-geoip", "soffchen/sing-geosite"),
        listOf("Chocolate4U/Iran-sing-box-rules", "Chocolate4U/Iran-sing-box-rules"),
        listOf("L11R/antizapret-sing-box-geo", "L11R/antizapret-sing-box-geo"))
    private val directory get() = app.externalAssets

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = LayoutAssetsBinding.inflate(layoutInflater)
        setContentView(layout.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_resources_button)
            setDisplayHomeAsUpEnabled(true)
        }
        layout.recyclerView.layoutManager = FixedLinearLayoutManager(layout.recyclerView)
        layout.recyclerView.adapter = adapter
        layout.refreshLayout.setOnRefreshListener { updateAll() }
        layout.resourceSource.setOnClickListener {
            if (busy) return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle(R.string.resource_source)
                .setSingleChoiceItems(R.array.rules_dat_provider, DataStore.rulesProvider.coerceIn(providers.indices)) { dialog, index ->
                    DataStore.rulesProvider = index
                    renderSource()
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        renderSource()
        adapter.reload()
    }

    private fun renderSource() {
        layout.resourceSource.text = getString(R.string.resource_source) + "\n" +
            resources.getStringArray(R.array.rules_dat_provider)[DataStore.rulesProvider.coerceIn(providers.indices)]
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        for (id in listOf(R.id.action_import_file, R.id.action_update_resources)) menu.findItem(id)?.isEnabled = !busy
        return super.onPrepareOptionsMenu(menu)
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (name == null || !ResourceFiles.validName(name)) {
                snackbar(R.string.resource_invalid).show()
            } else operation {
                val staged = File.createTempFile("import-", ".tmp", directory)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { output -> ResourceFiles.copy(input, output); output.fd.sync() }
                    } ?: error("Cannot open resource")
                    ResourceFiles.validate(staged, name)
                    currentCoroutineContext().ensureActive()
                    Os.rename(staged.path, File(directory, name).path)
                    versionFile(name).writeText("Custom")
                    1
                } finally { staged.delete() }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import_file -> { if (!busy) startFilesForResult(importFile, "*/*"); true }
        R.id.action_update_resources -> { updateAll(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun operation(work: suspend () -> Int) {
        if (busy) { snackbar(R.string.resource_busy).show(); return }
        busy = true
        layout.refreshLayout.isRefreshing = true
        layout.resourceSource.isEnabled = false
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { work() }
                snackbar(getString(R.string.resource_updated_count, count)).show()
                if (DataStore.serviceState.connected) snackbar(R.string.resource_changed).show()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@AssetsActivity).setTitle(R.string.error_title)
                    .setMessage(e.readableMessage).setPositiveButton(android.R.string.ok, null).show()
            } finally {
                busy = false
                layout.refreshLayout.isRefreshing = false
                layout.resourceSource.isEnabled = true
                invalidateOptionsMenu()
                adapter.reload()
            }
        }
    }

    private fun updateAll() {
        val source = DataStore.rulesProvider.coerceIn(providers.indices)
        operation {
            // Download and validate both files before replacing either existing database.
            val pending = mutableListOf<Pair<File, Pair<String, String>>>()
            try {
                for ((index, name) in assetNames.withIndex()) {
                    val (staged, version) = download(name, providers[source][index])
                    pending += staged to (name to version)
                }
                currentCoroutineContext().ensureActive()
                pending.forEach { (staged, metadata) ->
                    Os.rename(staged.path, File(directory, metadata.first).path)
                    versionFile(metadata.first).writeText(metadata.second)
                }
                pending.size
            } finally { pending.forEach { it.first.delete() } }
        }
    }

    private fun versionFile(name: String) = File(directory, name.substringBeforeLast('.') + ".version.txt")

    private fun download(name: String, repository: String): Pair<File, String> {
        val builder = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        if (DataStore.serviceState.connected) builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
        val client = builder.build()
        val staged = File.createTempFile("resource-", ".tmp", directory)
        try {
            val release = client.newCall(Request.Builder().url("https://api.github.com/repos/$repository/releases/latest").build()).execute().use { response ->
                check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
                val body = response.body ?: error("Empty release")
                val buffer = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val bytes = ByteArray(8192)
                    while (true) { val n = input.read(bytes); if (n < 0) break; check(buffer.size() + n <= 2 * 1024 * 1024); buffer.write(bytes, 0, n) }
                }
                JSONObject(buffer.toString("UTF-8"))
            }
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
                ?: error("$name missing in $repository release")
            val url = asset.getString("browser_download_url")
            require(url.startsWith("https://github.com/$repository/releases/download/"))
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Download HTTP ${response.code}" }
                val body = response.body ?: error("Empty resource")
                require(body.contentLength() <= ResourceFiles.LIMIT)
                body.byteStream().use { input -> staged.outputStream().use { output -> ResourceFiles.copy(input, output); output.fd.sync() } }
            }
            require(staged.length() == asset.getLong("size")) { "Incomplete resource download" }
            ResourceFiles.validate(staged, name)
            return staged to release.getString("tag_name")
        } catch (e: Exception) { staged.delete(); throw e
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>() {
        private var files: List<File> = emptyList()
        fun reload() {
            files = assetNames.map { File(directory, it) } +
                (directory.listFiles()?.filter { it.isFile && ResourceFiles.validName(it.name) && it.name !in assetNames }?.sortedBy { it.name } ?: emptyList())
            notifyDataSetChanged()
        }
        override fun getItemCount() = files.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: AssetHolder, position: Int) = holder.bind(files[position])
    }

    inner class AssetHolder(private val binding: LayoutAssetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.assetName.text = file.name
            binding.assetStatus.text = if (file.isFile) {
                Formatter.formatFileSize(this@AssetsActivity, file.length()) + " · " +
                    DateFormat.getDateFormat(this@AssetsActivity).format(Date(file.lastModified())) + " " +
                    DateFormat.getTimeFormat(this@AssetsActivity).format(Date(file.lastModified()))
            } else getString(R.string.resource_not_installed)
            binding.rulesUpdate.isVisible = file.name in assetNames
            binding.rulesUpdate.isEnabled = !busy
            binding.rulesUpdate.setOnClickListener {
                val source = DataStore.rulesProvider.coerceIn(providers.indices)
                operation {
                    val (staged, version) = download(file.name, providers[source][assetNames.indexOf(file.name)])
                    try {
                        currentCoroutineContext().ensureActive()
                        Os.rename(staged.path, file.path)
                        versionFile(file.name).writeText(version)
                        1
                    } finally { staged.delete() }
                }
            }
            binding.deleteResource.isEnabled = !busy && file.isFile
            binding.deleteResource.setOnClickListener {
                MaterialAlertDialogBuilder(this@AssetsActivity).setTitle(R.string.delete)
                    .setMessage(getString(R.string.resource_delete_confirm, file.name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> operation {
                        check(file.delete()) { "Cannot delete ${file.name}" }
                        versionFile(file.name).delete()
                        0
                    } }.show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
