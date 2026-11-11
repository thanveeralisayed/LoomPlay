package com.loomplay.core.data.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.LoomMediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreVideoScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mpvRecommendedExtensions = setOf("mkv", "avi", "rmvb", "flv", "ts", "m2ts", "vob")

    private val manualRescanTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val thumbnailCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun triggerRescan() {
        manualRescanTrigger.tryEmit(Unit)
    }

    fun clearThumbnailCache() {
        thumbnailCache.evictAll()
    }

    fun observeDeviceVideos(): Flow<List<LoomMediaItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val collection = videoCollectionUri()
        runCatching {
            context.contentResolver.registerContentObserver(collection, true, observer)
        }
        trySend(Unit)
        val rescanJob = launch {
            manualRescanTrigger.collect {
                trySend(Unit)
            }
        }
        awaitClose {
            rescanJob.cancel()
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }.conflate().let { triggerFlow ->
        flow {
            triggerFlow.collect {
                emit(scanVideos())
            }
        }
    }

    suspend fun scanVideos(): List<LoomMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LoomMediaItem>()
        val collection = videoCollectionUri()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(nameCol) ?: "Video $id"
                    val folder = cursor.getString(bucketCol) ?: "Device"
                    val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                    val sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L)
                    val width = cursor.getInt(widthCol).coerceAtLeast(0)
                    val height = cursor.getInt(heightCol).coerceAtLeast(0)
                    val mimeType = cursor.getString(mimeCol)
                    val dateAdded = cursor.getLong(dateCol).coerceAtLeast(0L)
                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val ext = title.substringAfterLast('.', "").lowercase()
                    val recommended = if (ext in mpvRecommendedExtensions) {
                        EngineType.MPV
                    } else {
                        EngineType.EXOPLAYER
                    }

                    items += LoomMediaItem(
                        uri = contentUri,
                        title = title,
                        folderName = folder,
                        durationMs = durationMs,
                        fileSizeBytes = sizeBytes,
                        width = width,
                        height = height,
                        mimeType = mimeType,
                        dateAddedEpochSec = dateAdded,
                        recommendedEngine = recommended,
                    )
                }
            }
        }
        items
    }

    suspend fun loadThumbnail(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache.get(uriString)?.let { return@withContext it }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(480, 270), null)
            } else {
                @Suppress("DEPRECATION")
                val id = ContentUris.parseId(uri)
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null,
                )
            }
        }.getOrNull()
        if (bitmap != null) {
            thumbnailCache.put(uriString, bitmap)
        }
        bitmap
    }

    private fun videoCollectionUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
}
