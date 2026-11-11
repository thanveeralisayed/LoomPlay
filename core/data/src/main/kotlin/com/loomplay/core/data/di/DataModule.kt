package com.loomplay.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.loomplay.core.data.local.LoomDatabase
import com.loomplay.core.data.local.dao.PlaybackHistoryDao
import com.loomplay.core.data.local.dao.PlaylistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS media_metadata")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideLoomDatabase(
        @ApplicationContext context: Context,
    ): LoomDatabase = Room.databaseBuilder(
        context,
        LoomDatabase::class.java,
        "loomplay.db",
    )
        .addMigrations(MIGRATION_1_2)
        .build()

    @Provides
    fun providePlaybackHistoryDao(db: LoomDatabase): PlaybackHistoryDao = db.playbackHistoryDao()

    @Provides
    fun providePlaylistDao(db: LoomDatabase): PlaylistDao = db.playlistDao()
}
