package com.loomplay.core.mediasession

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MediaSessionService host connecting LoomPlay playback to lock-screen,
 * Bluetooth headset, notification controls, AudioFocus, and becoming-noisy handling.
 */
@AndroidEntryPoint
class LoomMediaSessionService : MediaSessionService() {

    @Inject
    lateinit var sessionBridge: LoomPlaybackSessionBridge

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var audioManager: AudioManager
    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false
    private var noisyReceiverRegistered = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnFocusGain = false
                sessionBridge.pause()
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                sessionBridge.setAudioFocusDucked(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    sessionBridge.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeOnFocusGain = false
                abandonAudioFocus()
                sessionBridge.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                val status = sessionBridge.playbackState.value
                resumeOnFocusGain = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING
                sessionBridge.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                sessionBridge.setAudioFocusDucked(true)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)

        val playerAdapter = LoomMedia3PlayerAdapter(
            bridge = sessionBridge,
            onRequestAudioFocus = ::requestPlaybackAudioFocus,
            onStopRequested = {
                abandonAudioFocus()
                unregisterBecomingNoisy()
                stopSelf()
            },
        )

        val builder = MediaSession.Builder(this, playerAdapter)
        buildSessionActivityPendingIntent(sessionBridge.activeHostActivityClassName.value)
            ?.let(builder::setSessionActivity)
        mediaSession = builder.build()

        serviceScope.launch {
            sessionBridge.activeHostActivityClassName.collect { hostClassName ->
                buildSessionActivityPendingIntent(hostClassName)?.let { pendingIntent ->
                    mediaSession?.setSessionActivity(pendingIntent)
                }
            }
        }

        serviceScope.launch {
            sessionBridge.playbackState.collect { status ->
                when (status) {
                    PlaybackStatus.PLAYING -> {
                        if (requestPlaybackAudioFocus()) {
                            registerBecomingNoisy()
                        } else {
                            sessionBridge.pause()
                        }
                    }
                    PlaybackStatus.BUFFERING -> {
                        if (hasAudioFocus) {
                            registerBecomingNoisy()
                        }
                    }
                    PlaybackStatus.PAUSED,
                    PlaybackStatus.READY,
                    PlaybackStatus.ENDED -> {
                        unregisterBecomingNoisy()
                        if (!resumeOnFocusGain) {
                            abandonAudioFocus()
                        }
                    }
                    PlaybackStatus.IDLE,
                    PlaybackStatus.ERROR -> {
                        if (sessionBridge.mediaMetadata.value.uri.isBlank()) {
                            resumeOnFocusGain = false
                        }
                        unregisterBecomingNoisy()
                        if (!resumeOnFocusGain) {
                            abandonAudioFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val status = sessionBridge.playbackState.value
        val active = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING
        if (!active) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterBecomingNoisy()
        abandonAudioFocus()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun requestPlaybackAudioFocus(): Boolean {
        if (hasAudioFocus) {
            sessionBridge.setAudioFocusDucked(false)
            return true
        }
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
            .also { audioFocusRequest = it }

        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasAudioFocus = granted
        if (granted) {
            sessionBridge.setAudioFocusDucked(false)
        }
        return granted
    }

    private fun abandonAudioFocus() {
        hasAudioFocus = false
        audioFocusRequest?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
    }

    private fun registerBecomingNoisy() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiverRegistered = true
    }

    private fun buildSessionActivityPendingIntent(hostClassName: String?): PendingIntent? {
        val targetIntent = hostClassName?.let { className ->
            runCatching {
                Intent(this, Class.forName(className)).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
            }.getOrNull()
        } ?: packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return targetIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private fun unregisterBecomingNoisy() {
        if (!noisyReceiverRegistered) return
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        noisyReceiverRegistered = false
    }
}
