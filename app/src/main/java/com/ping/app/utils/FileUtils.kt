// utils/FileUtils.kt
package com.ping.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileUtils @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val pingDir: File by lazy {
        File(context.filesDir, "ping_files").also { it.mkdirs() }
    }
    
    private val thumbnailDir: File by lazy {
        File(context.cacheDir, "thumbnails").also { it.mkdirs() }
    }
    
    fun getFileInfo(uri: Uri): FileInfo {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        
        return cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            it.moveToFirst()
            
            val name = it.getString(nameIndex)
            val size = it.getLong(sizeIndex)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            
            FileInfo(name, size, mimeType)
        } ?: throw IllegalArgumentException("Cannot read file info")
    }
    
    fun readFileBytes(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes()
        } ?: throw IllegalArgumentException("Cannot read file")
    }
    
    fun saveReceivedFile(fileName: String, mimeType: String, data: ByteArray): String {
        val subDir = when {
            mimeType.startsWith("image/") -> "images"
            mimeType.startsWith("video/") -> "videos"
            mimeType.startsWith("audio/") -> "audio"
            else -> "files"
        }
        
        val dir = File(pingDir, subDir).also { it.mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}_$fileName")
        
        FileOutputStream(file).use { outputStream ->
            outputStream.write(data)
        }
        
        return file.absolutePath
    }
    
    fun createThumbnail(filePath: String, mimeType: String): String? {
        return try {
            val bitmap = when {
                mimeType.startsWith("image/") -> {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4
                    }
                    BitmapFactory.decodeFile(filePath, options)
                }
                mimeType.startsWith("video/") -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(
                            File(filePath),
                            Size(200, 200),
                            null
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(
                            filePath,
                            MediaStore.Images.Thumbnails.MINI_KIND
                        )
                    }
                }
                else -> null
            }
            
            bitmap?.let { bmp ->
                val thumbnailFile = File(thumbnailDir, "${System.currentTimeMillis()}_thumb.jpg")
                FileOutputStream(thumbnailFile).use { outputStream ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                }
                thumbnailFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            false
        }
    }
    
    fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    fun getFileSizeFormatted(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

data class FileInfo(
    val name: String,
    val size: Long,
    val mimeType: String
)
