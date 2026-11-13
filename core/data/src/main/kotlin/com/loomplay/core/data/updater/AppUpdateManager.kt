package com.loomplay.core.data.updater

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.loomplay.core.model.AppUpdateInfo
import com.loomplay.core.model.UpdateDownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    companion object {
        private const val GITHUB_REPO_API = "https://api.github.com/repos/thanveeralisayed/LoomPlay/releases/latest"

        internal fun isVersionNewer(remote: String, current: String): Boolean {
            val rParts = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
            val cParts = current.split('.', '-').mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }

    suspend fun checkForUpdates(currentVersionName: String): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            _downloadState.value = UpdateDownloadState.Checking
            val url = URL(GITHUB_REPO_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "LoomPlay-AppUpdater")
            }

            if (connection.responseCode !in 200..299) {
                val errorMsg = "GitHub API returned HTTP ${connection.responseCode}"
                _downloadState.value = UpdateDownloadState.Error(errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(body).jsonObject

            val tagName = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
            val remoteVersion = tagName.removePrefix("v").trim()
            val bodyText = root["body"]?.jsonPrimitive?.content.orEmpty()

            val assets = root["assets"]?.jsonArray.orEmpty()
            val preferredAbi = getPrimaryAbi()

            var matchedAsset = assets.firstOrNull { elem ->
                val name = elem.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                name.endsWith(".apk", ignoreCase = true) && name.contains(preferredAbi, ignoreCase = true)
            }?.jsonObject

            if (matchedAsset == null) {
                matchedAsset = assets.firstOrNull { elem ->
                    val name = elem.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    name.endsWith(".apk", ignoreCase = true) && name.contains("universal", ignoreCase = true)
                }?.jsonObject
            }

            if (matchedAsset == null) {
                matchedAsset = assets.firstOrNull { elem ->
                    val name = elem.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    name.endsWith(".apk", ignoreCase = true)
                }?.jsonObject
            }

            if (matchedAsset == null) {
                val err = "No APK assets found in latest release $tagName"
                _downloadState.value = UpdateDownloadState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            val downloadUrl = matchedAsset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
            val apkName = matchedAsset["name"]?.jsonPrimitive?.content.orEmpty()
            val apkSize = matchedAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

            val isNewer = isVersionNewer(remoteVersion, currentVersionName)
            val info = AppUpdateInfo(
                latestVersionName = remoteVersion,
                releaseNotes = bodyText,
                downloadUrl = downloadUrl,
                apkFileName = apkName,
                apkSizeBytes = apkSize,
                isUpdateAvailable = isNewer,
            )

            if (isNewer) {
                _downloadState.value = UpdateDownloadState.Available(info)
            } else {
                _downloadState.value = UpdateDownloadState.Idle
            }

            Result.success(info)
        } catch (t: Throwable) {
            _downloadState.value = UpdateDownloadState.Error(t.localizedMessage ?: "Failed to check for updates")
            Result.failure(t)
        }
    }

    suspend fun downloadAndPrepareApk(updateInfo: AppUpdateInfo): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            _downloadState.value = UpdateDownloadState.Downloading(0, 0L, updateInfo.apkSizeBytes)

            val downloadDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(downloadDir, updateInfo.apkFileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val url = URL(updateInfo.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else updateInfo.apkSizeBytes
            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportTime = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime >= 200L || totalRead == totalBytes) {
                            lastReportTime = now
                            val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                            _downloadState.value = UpdateDownloadState.Downloading(percent, totalRead, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile,
            )

            _downloadState.value = UpdateDownloadState.ReadyToInstall(updateInfo, contentUri.toString())
            Result.success(contentUri)
        } catch (t: Throwable) {
            _downloadState.value = UpdateDownloadState.Error(t.localizedMessage ?: "Download failed")
            Result.failure(t)
        }
    }

    fun resetState() {
        _downloadState.value = UpdateDownloadState.Idle
    }

    private fun getPrimaryAbi(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return when {
            abi.startsWith("arm64") -> "arm64-v8a"
            abi.startsWith("armeabi") -> "armeabi-v7a"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    internal fun isVersionNewer(remote: String, current: String): Boolean {
        val rParts = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val cParts = current.split('.', '-').mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}

