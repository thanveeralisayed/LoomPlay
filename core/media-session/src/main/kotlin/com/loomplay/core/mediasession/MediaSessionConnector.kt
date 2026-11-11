package com.loomplay.core.mediasession

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.loomplay.core.player.api.LoomMediaSessionConnector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaSessionConnectorImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LoomMediaSessionConnector {

    private val lock = Any()
    private val activeOwners = mutableSetOf<Any>()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun connectSession(ownerToken: Any) {
        synchronized(lock) {
            activeOwners += ownerToken
            if (controllerFuture != null) return
            val token = SessionToken(
                context,
                ComponentName(context, LoomMediaSessionService::class.java),
            )
            val future = MediaController.Builder(context, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    runCatching { future.get() }.onFailure {
                        synchronized(lock) {
                            if (controllerFuture === future) {
                                controllerFuture = null
                            }
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    override fun disconnectSession(ownerToken: Any) {
        synchronized(lock) {
            activeOwners -= ownerToken
            if (activeOwners.isEmpty()) {
                controllerFuture?.let { future ->
                    MediaController.releaseFuture(future)
                    controllerFuture = null
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaSessionModule {

    @Binds
    @Singleton
    abstract fun bindMediaSessionConnector(impl: MediaSessionConnectorImpl): LoomMediaSessionConnector
}

