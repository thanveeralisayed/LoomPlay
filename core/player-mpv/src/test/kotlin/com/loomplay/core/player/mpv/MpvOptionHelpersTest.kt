package com.loomplay.core.player.mpv

import com.loomplay.core.player.api.LoomPlayerError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvOptionHelpersTest {

    @Test
    fun `encodeMpvHeaderFields uses length prefixing and excludes UA and Referer`() {
        val headers = linkedMapOf(
            "User-Agent" to "CustomUA/1.0",
            "Referer" to "https://ref.example",
            "Cookie" to "a=1, b=2; c=3",
            "Authorization" to "Bearer token123",
        )
        val encoded = encodeMpvHeaderFields(headers)
        val first = "Cookie: a=1, b=2; c=3"
        val second = "Authorization: Bearer token123"
        assertEquals("%${first.toByteArray().size}%$first%${second.toByteArray().size}%$second", encoded)
    }

    @Test
    fun `encodeMpvHeaderFields strips CR and LF characters`() {
        val encoded = encodeMpvHeaderFields(mapOf("X-Custom" to "val1\r\nInjected: bad"))
        val expectedLine = "X-Custom: val1Injected: bad"
        assertEquals("%${expectedLine.toByteArray().size}%$expectedLine", encoded)
    }

    @Test
    fun `parseMpvConfigLines skips comments and blank lines`() {
        val raw = """
            # Comment line
            profile=high-quality

            scale = spline36
            deband
        """.trimIndent()
        val parsed = parseMpvConfigLines(raw)
        assertEquals(
            listOf(
                "profile" to "high-quality",
                "scale" to "spline36",
                "deband" to "yes",
            ),
            parsed,
        )
    }

    @Test
    fun `computeMpvMarginY scales percentage to 720p coordinate space`() {
        assertEquals(14, computeMpvMarginY(1)) // clamped to 2%
        assertEquals(29, computeMpvMarginY(4))
        assertEquals(72, computeMpvMarginY(10))
        assertEquals(144, computeMpvMarginY(25)) // clamped to 20%
    }

    @Test
    fun `mapMpvError classifies format network and generic failures`() {
        val formatErr = mapMpvError("[lavf] Failed to recognize file format.", hadLoadedFile = false)
        assertTrue(formatErr is LoomPlayerError.UnsupportedFormat)

        val netErr = mapMpvError("[ffmpeg] HTTP error 403 Forbidden", hadLoadedFile = false)
        assertTrue(netErr is LoomPlayerError.NetworkOrIo)

        val generic = mapMpvError(null, hadLoadedFile = true)
        assertTrue(generic is LoomPlayerError.PlaybackFailure)
        assertTrue(generic.message.contains("unexpectedly"))
    }
}

