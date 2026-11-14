package com.loomplay.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.loomplay.core.ui.image.LoomImageLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LoomPlayApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return LoomImageLoader.build(context)
    }
}


