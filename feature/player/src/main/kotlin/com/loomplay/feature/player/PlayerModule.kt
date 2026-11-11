package com.loomplay.feature.player

import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackSessionBridge(impl: PlayerManager): LoomPlaybackSessionBridge
}

