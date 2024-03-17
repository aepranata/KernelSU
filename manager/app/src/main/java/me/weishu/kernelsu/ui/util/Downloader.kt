package me.weishu.kernelsu.ui.util

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import me.weishu.kernelsu.getKMI

/**
 * @author weishu
 * @date 2023/6/22.
 */
@SuppressLint("Range")
fun download(
    context: Context,
    url: String,
    fileName: String,
    description: String,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {}
) {
    val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val query = DownloadManager.Query()
    query.setFilterByStatus(DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_PENDING)
    downloadManager.query(query).use { cursor ->
        while (cursor.moveToNext()) {
            val uri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI))
            val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
            val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
            val columnTitle = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE))
            if (url == uri || fileName == columnTitle) {
                if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                    onDownloading()
                    return
                } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    onDownloaded(Uri.parse(localUri))
                    return
                }
            }
        }
    }

    val request = DownloadManager.Request(Uri.parse(url))
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            fileName
        )
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setMimeType("application/zip")
        .setTitle(fileName)
        .setDescription(description)

    downloadManager.enqueue(request)
}

fun checkNewVersion(): Triple<Int, String, String> {
    val url = "https://api.github.com/repos/tiann/KernelSU/releases/latest"
    val defaultValue = Triple(0, "", "")
    runCatching {
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val body = response.body?.string() ?: return defaultValue
                val json = org.json.JSONObject(body)
                val changelog = json.optString("body")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (!name.endsWith(".apk")) {
                        continue
                    }

                    val regex = Regex("v(.+?)_(\\d+)-")
                    val matchResult = regex.find(name) ?: continue
                    val versionName = matchResult.groupValues[1]
                    val versionCode = matchResult.groupValues[2].toInt()
                    val downloadUrl = asset.getString("browser_download_url")

                    return Triple(versionCode, downloadUrl, changelog)
                }

            }
    }
    return defaultValue
}
fun getLKMUrl(): Result<Pair<String, String>> {
    val url = "https://api.github.com/repos/tiann/KernelSU/releases/latest"

    val kmi = getKMI() ?: return Result.failure(RuntimeException("Get KMI failed"))
    runCatching {
        okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute()
            .use { response ->
                val body = response.body?.string() ?: return Result.failure(RuntimeException("request body failed"))
                if (!response.isSuccessful) {
                    return Result.failure(RuntimeException("Request failed, code: ${response.code}, message: $body"))
                }
                val json = org.json.JSONObject(body)

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (!name.endsWith(".ko")) {
                        continue
                    }

                    if (name.contains(kmi)) {
                        return Result.success(Pair(name, asset.getString("browser_download_url")))
                    }
                }
            }
    }.onFailure {
        return Result.failure(it)
    }

    return Result.failure(RuntimeException("Cannot find LKM for $kmi"))
}

@Composable
fun DownloadListener(context: Context, onDownloaded: (Uri) -> Unit) {
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("Range")
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val id = intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1
                    )
                    val query = DownloadManager.Query().setFilterById(id)
                    val downloadManager =
                        context?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(
                            cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        )
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = cursor.getString(
                                cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            )
                            onDownloaded(Uri.parse(uri))
                        }
                    }
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}