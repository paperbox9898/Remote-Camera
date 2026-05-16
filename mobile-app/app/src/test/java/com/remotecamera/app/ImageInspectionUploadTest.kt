package com.remotecamera.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageInspectionUploadTest {
    @Test
    fun acceptsCommonImageMimeTypesForInspectionUpload() {
        assertTrue(isSupportedInspectionImageMimeType("image/jpeg"))
        assertTrue(isSupportedInspectionImageMimeType("image/png"))
        assertTrue(isSupportedInspectionImageMimeType("image/webp"))
    }

    @Test
    fun rejectsNonImageMimeTypesForInspectionUpload() {
        assertFalse(isSupportedInspectionImageMimeType("application/pdf"))
        assertFalse(isSupportedInspectionImageMimeType("text/plain"))
        assertFalse(isSupportedInspectionImageMimeType(""))
    }

    @Test
    fun resolvesMimeTypeFromFileNameWhenProviderDoesNotReturnOne() {
        assertEquals("image/jpeg", inspectionImageMimeType("", "camera.JPG"))
        assertEquals("image/png", inspectionImageMimeType("application/octet-stream", "sample.png"))
        assertEquals("image/webp", inspectionImageMimeType("", "preview.webp"))
    }
}
