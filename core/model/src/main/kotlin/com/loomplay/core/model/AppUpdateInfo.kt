package com.loomplay.core.model

data class AppUpdateInfo(
    val latestVersionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long = 0L,
    val isUpdateAvailable: Boolean = false,
)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data object Checking : UpdateDownloadState
    data class Available(val updateInfo: AppUpdateInfo) : UpdateDownloadState
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateDownloadState
    data class ReadyToInstall(val updateInfo: AppUpdateInfo, val apkUriString: String) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}
