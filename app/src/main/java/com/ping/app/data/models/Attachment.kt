// data/models/Attachment.kt
package com.ping.app.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val localPath: String? = null,
    val encryptedData: ByteArray? = null,
    val thumbnailPath: String? = null,
    val duration: Long? = null, // For audio/video
    val width: Int? = null,
    val height: Int? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.PENDING,
    val uploadProgress: Int = 0,
    val downloadProgress: Int = 0,
    val checksum: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {
    
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
    
    fun isWithinSizeLimit(): Boolean = fileSize <= MAX_FILE_SIZE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Attachment
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    }
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED
}
