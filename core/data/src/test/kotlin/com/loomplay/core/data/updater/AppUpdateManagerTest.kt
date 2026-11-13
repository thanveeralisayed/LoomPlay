package com.loomplay.core.data.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `version comparison identifies newer and older versions correctly`() {
        assertTrue(AppUpdateManager.isVersionNewer("0.1.5", "0.1.4"))
        assertTrue(AppUpdateManager.isVersionNewer("0.2.0", "0.1.4"))
        assertTrue(AppUpdateManager.isVersionNewer("1.0.0", "0.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("0.1.4.1", "0.1.4"))

        assertFalse(AppUpdateManager.isVersionNewer("0.1.4", "0.1.4"))
        assertFalse(AppUpdateManager.isVersionNewer("0.1.3", "0.1.4"))
        assertFalse(AppUpdateManager.isVersionNewer("0.0.9", "0.1.0"))
    }
}

