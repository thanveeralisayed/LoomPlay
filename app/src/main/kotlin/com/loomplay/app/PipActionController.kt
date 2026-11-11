package com.loomplay.app

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.LoomPlaybackSessionBridge

internal class PipActionController(
    private val activity: Activity,
    private val sessionBridge: LoomPlaybackSessionBridge,
) {
    companion object {
        private const val ACTION_PIP_CONTROL = "com.loomplay.app.PIP_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_REWIND = 1
        private const val CONTROL_PLAY_PAUSE = 2
        private const val CONTROL_FORWARD = 3
    }

    var isPlayerActive: Boolean = false
    var isPlaybackPlaying: Boolean = false
    var pipAutoEnterPref: Boolean = true
    var backgroundAudioEnabled: Boolean = false
    var currentVideoSize: VideoSize = VideoSize()
    var pipVideoRect: Rect? = null

    var autoPipSuppressed: Boolean = false
        private set
    private var pipParamsConfigured: Boolean = false
    private var receiverRegistered: Boolean = false

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            if (!activity.isInPictureInPictureMode) return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_REWIND -> {
                    sessionBridge.seekTo((sessionBridge.positionMs.value - 10_000L).coerceAtLeast(0L))
                }
                CONTROL_PLAY_PAUSE -> {
                    val status = sessionBridge.playbackState.value
                    if (status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING) {
                        sessionBridge.pause()
                    } else {
                        sessionBridge.play()
                    }
                }
                CONTROL_FORWARD -> {
                    sessionBridge.seekTo(sessionBridge.positionMs.value + 10_000L)
                }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { activity.unregisterReceiver(pipActionReceiver) }
        receiverRegistered = false
    }

    fun suppressAutoPip() {
        autoPipSuppressed = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isPipSupported()) {
            runCatching {
                activity.setPictureInPictureParams(buildPipParams(autoEnter = false))
            }
        }
    }

    fun resetAutoPipSuppression() {
        if (!autoPipSuppressed) return
        autoPipSuppressed = false
        refreshPipParams()
    }

    internal fun shouldAutoEnterPip(): Boolean =
        isPlayerActive && isPlaybackPlaying && pipAutoEnterPref && !backgroundAudioEnabled && !autoPipSuppressed

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && shouldAutoEnterPip()) {
            enterPipIfSupported()
        }
    }

    fun refreshPipParams() {
        if (!isPipSupported()) return
        val inPip = activity.isInPictureInPictureMode
        if (inPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (isPlayerActive || pipParamsConfigured))) {
            val autoEnter = shouldAutoEnterPip()
            runCatching {
                activity.setPictureInPictureParams(buildPipParams(autoEnter))
                pipParamsConfigured = isPlayerActive
            }
        }
    }

    fun enterPipIfSupported() {
        if (!isPipSupported()) return
        runCatching {
            activity.enterPictureInPictureMode(buildPipParams(false))
            pipParamsConfigured = true
        }
    }

    private fun isPipSupported(): Boolean =
        activity.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true

    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val ratio = if (currentVideoSize.width > 0 && currentVideoSize.height > 0) {
            val raw = currentVideoSize.width.toFloat() / currentVideoSize.height.toFloat()
            when {
                raw < 0.42f -> Rational(42, 100)
                raw > 2.39f -> Rational(239, 100)
                else -> Rational(currentVideoSize.width, currentVideoSize.height)
            }
        } else {
            Rational(16, 9)
        }

        val actions = listOf(
            buildPipAction(CONTROL_REWIND, R.drawable.ic_pip_replay_10, "Rewind 10s"),
            if (isPlaybackPlaying) {
                buildPipAction(CONTROL_PLAY_PAUSE, R.drawable.ic_pip_pause, "Pause")
            } else {
                buildPipAction(CONTROL_PLAY_PAUSE, R.drawable.ic_pip_play, "Play")
            },
            buildPipAction(CONTROL_FORWARD, R.drawable.ic_pip_forward_10, "Forward 10s"),
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(actions)

        pipVideoRect?.takeIf { !it.isEmpty && it.left >= 0 && it.top >= 0 }?.let {
            builder.setSourceRectHint(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun buildPipAction(controlType: Int, iconRes: Int, title: String): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).apply {
            setPackage(activity.packageName)
            putExtra(EXTRA_CONTROL_TYPE, controlType)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            controlType,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(activity, iconRes)
        return RemoteAction(icon, title, title, pendingIntent)
    }
}

