package com.remotecamera.app

fun isSupportedInspectionImageMimeType(mimeType: String): Boolean {
    return mimeType == "image/jpeg" ||
        mimeType == "image/jpg" ||
        mimeType == "image/png" ||
        mimeType == "image/webp"
}

fun inspectionImageMimeType(declaredMimeType: String, fileName: String): String {
    val normalized = declaredMimeType.lowercase()
    if (isSupportedInspectionImageMimeType(normalized)) {
        return if (normalized == "image/jpg") "image/jpeg" else normalized
    }

    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> normalized
    }
}
