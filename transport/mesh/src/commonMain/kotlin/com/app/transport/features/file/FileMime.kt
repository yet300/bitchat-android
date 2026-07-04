package com.app.transport.features.file

/**
 * Pure (commonMain) MIME-type guess from a file name's extension. Extracted so both the androidMain
 * `FileUtils` and the platform-free senders (:core:data) share one table. Falls back to
 * `application/octet-stream` for unknown/empty extensions.
 */
fun mimeTypeFromExtension(fileName: String): String =
    when (fileName.substringAfterLast(".", "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        else -> "application/octet-stream"
    }
