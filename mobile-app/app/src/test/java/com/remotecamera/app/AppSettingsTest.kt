package com.remotecamera.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun normalizeServerUrlTrimsWhitespaceAndTrailingSlash() {
        assertEquals(
            "http://10.0.2.2:8000",
            normalizeServerUrl("  http://10.0.2.2:8000/  "),
        )
    }
}
