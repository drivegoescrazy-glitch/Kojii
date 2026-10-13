// Copyright 2026 Kojii contributors
// Licensed under GPLv3 or any later version.

package org.citra.citra_emu.ui.main

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.R
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lets users select a published Kojii GitHub release and install one of its APK assets.
 */
object KojiiVersionSelector {
    private const val RELEASES_API =
        "https://api.github.com/repos/drivegoescrazy-glitch/Kojii/releases?per_page=100"

    private data class ApkAsset(val name: String, val url: String)
    private data class Release(val tag: String, val name: String, val assets: List<ApkAsset>) {
        val label: String
            get() = if (name.isBlank() || name == tag) tag else "$tag — $name"
    }

    fun show(activity: AppCompatActivity) {
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.kojii_versions)
            .setMessage(R.string.kojii_versions_loading)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        progress.show()

        activity.lifecycleScope.launch {
            val releases = withContext(Dispatchers.IO) {
                runCatching { fetchReleases() }.getOrNull()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            progress.dismiss()

            if (releases == null) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.kojii_versions)
                    .setMessage(R.string.kojii_versions_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            if (releases.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.kojii_versions)
                    .setMessage(R.string.kojii_versions_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("Open GitHub releases") { _, _ ->
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/drivegoescrazy-glitch/Kojii/releases"
                            ))
                        )
                    }
                    .show()
                return@launch
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.kojii_versions)
                .setItems(releases.map { it.label }.toTypedArray()) { _, index ->
                    chooseAsset(activity, releases[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Latest on GitHub") { _, _ ->
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://github.com/drivegoescrazy-glitch/Kojii/releases"
                        ))
                    )
                }
                .show()
        }
    }

    private fun chooseAsset(activity: AppCompatActivity, release: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.kojii_versions_install_permission)
                .setMessage(R.string.kojii_versions_install_permission_description)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .show()
            return
        }

        val assets = release.assets
        if (assets.size == 1) {
            download(activity, release, assets[0])
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(release.label)
                .setItems(assets.map { it.name }.toTypedArray()) { _, index ->
                    download(activity, release, assets[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun download(activity: AppCompatActivity, release: Release, asset: ApkAsset) {
        runCatching {
            val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val safeName = asset.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val request = DownloadManager.Request(Uri.parse(asset.url))
                .setTitle(activity.getString(R.string.kojii_versions_downloading, release.tag))
                .setDescription(safeName)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Kojii-$safeName")
            val downloadId = manager.enqueue(request)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE ||
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId
                    ) return

                    runCatching { context.unregisterReceiver(this) }
                    val downloadedUri = manager.getUriForDownloadedFile(downloadId)
                    if (downloadedUri == null) {
                        Toast.makeText(context, R.string.kojii_versions_download_failed, Toast.LENGTH_LONG).show()
                        return
                    }
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(downloadedUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(installIntent) }
                        .onFailure {
                            Toast.makeText(context, R.string.kojii_versions_download_failed, Toast.LENGTH_LONG).show()
                        }
                }
            }
            val appContext = activity.applicationContext
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            Toast.makeText(
                activity,
                activity.getString(R.string.kojii_versions_downloading, release.tag),
                Toast.LENGTH_LONG
            ).show()
        }.onFailure {
            MaterialAlertDialogBuilder(activity)
                .setMessage(R.string.kojii_versions_download_failed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun fetchReleases(): List<Release> {
        val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Kojii-Android")
            if (connection.responseCode !in 200..299) error("GitHub API returned ${connection.responseCode}")

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            val releases = mutableListOf<Release>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val tag = item.optString("tag_name")
                if (tag.isBlank()) continue
                val assetsJson = item.optJSONArray("assets") ?: JSONArray()
                val assets = mutableListOf<ApkAsset>()
                for (j in 0 until assetsJson.length()) {
                    val asset = assetsJson.getJSONObject(j)
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                        assets += ApkAsset(name, url)
                    }
                }
                if (assets.isNotEmpty()) {
                    releases += Release(tag, item.optString("name"), assets)
                }
            }
            return releases
        } finally {
            connection.disconnect()
        }
    }
}
