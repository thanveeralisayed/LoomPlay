package com.loomplay.core.ui.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import okio.Path.Companion.toOkioPath

object LoomImageLoader {

    fun build(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailKeyer())
                add(MediaThumbnailFetcher.Factory(context))
                add(VideoFrameDecoder.Factory())
                add(OkHttpNetworkFetcherFactory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}

