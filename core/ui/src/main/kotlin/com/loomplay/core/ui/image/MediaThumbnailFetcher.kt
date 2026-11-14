package com.loomplay.core.ui.image

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Custom Coil 3 Keyer for MediaStore and video thumbnail URIs.
 */
class MediaThumbnailKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? {
        val scheme = data.scheme?.lowercase() ?: return null
        return if (scheme == "content" || scheme == "file") {
            "media_thumb_${data}_${options.size.width}x${options.size.height}"
        } else {
            null
        }
    }
}

/**
 * High-performance Coil 3 Fetcher utilizing Android's hardware/OS-optimized ContentResolver.loadThumbnail
 * for instant, downscaled video thumbnails with automatic bitmap recycling and cancellation.
 */
class MediaThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val scheme = uri.scheme?.lowercase() ?: return@withContext null
        if (scheme != "content" && scheme != "file") return@withContext null

        val targetWidth = 480
        val targetHeight = 270

        val bitmap: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(targetWidth, targetHeight), null)
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
            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        } else {
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme?.lowercase()
            return if (scheme == "content" || scheme == "file") {
                MediaThumbnailFetcher(context, data, options)
            } else {
                null
            }
        }
    }
}

