package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.data.SavedMedia
import com.example.data.SavedMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class FileSaver(
    private val context: Context,
    private val repository: SavedMediaRepository
) {
    private val client = OkHttpClient()

    suspend fun saveBase64Image(base64Data: String, mimeType: String, suggestedName: String? = null): SavedMedia? {
        return withContext(Dispatchers.IO) {
            try {
                var cleanMimeType = if (mimeType.isEmpty() || mimeType == "image/*" || mimeType.startsWith("application/octet-stream")) {
                     detectMimeFromBase64(base64Data) ?: "image/jpeg"
                } else mimeType

                if (cleanMimeType.contains("html", ignoreCase = true) || base64Data.startsWith("data:text/html") || base64Data.startsWith("data:text/plain")) {
                    return@withContext null
                }

                val commaIndex = base64Data.indexOf(",")
                val cleanBase64 = if (commaIndex != -1) base64Data.substring(commaIndex + 1) else base64Data
                var bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)

                if (cleanMimeType != "image/jpeg" && cleanMimeType != "image/jpg" && cleanMimeType != "image/webp") {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val stream = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
                            bytes = stream.toByteArray()
                            cleanMimeType = "image/jpeg"
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (cleanMimeType != "image/jpeg" && cleanMimeType != "image/jpg" && cleanMimeType != "image/webp") {
                    return@withContext null
                }

                val extension = if (cleanMimeType == "image/webp") "webp" else "jpeg"
                val finalMime = if (cleanMimeType == "image/webp") "image/webp" else "image/jpeg"
                val finalName = "GamerEdit_${System.currentTimeMillis()}.$extension"
                saveBytesToMediaStore(bytes, finalName, finalMime)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun downloadAndSaveUrl(url: String, mimeType: String, suggestedName: String? = null): SavedMedia? {
        return withContext(Dispatchers.IO) {
            try {
                if (url.endsWith(".html", ignoreCase = true) || url.contains(".html?") || mimeType.contains("html", ignoreCase = true)) {
                    return@withContext null
                }

                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val responseBody = response.body ?: return@withContext null
                    var bytes = responseBody.bytes()

                    var cleanMimeType = responseBody.contentType()?.toString()?.split(";")?.firstOrNull()?.trim() 
                        ?: mimeType.ifEmpty { "image/jpeg" }

                    if (cleanMimeType.contains("html", ignoreCase = true)) {
                        return@withContext null
                    }

                    if (cleanMimeType != "image/jpeg" && cleanMimeType != "image/jpg" && cleanMimeType != "image/webp") {
                        try {
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val stream = java.io.ByteArrayOutputStream()
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
                                bytes = stream.toByteArray()
                                cleanMimeType = "image/jpeg"
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (cleanMimeType != "image/jpeg" && cleanMimeType != "image/jpg" && cleanMimeType != "image/webp") {
                        return@withContext null
                    }

                    val extension = if (cleanMimeType == "image/webp") "webp" else "jpeg"
                    val finalMime = if (cleanMimeType == "image/webp") "image/webp" else "image/jpeg"
                    val finalName = "GamerEdit_${System.currentTimeMillis()}.$extension"
                    saveBytesToMediaStore(bytes, finalName, finalMime)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private suspend fun saveBytesToMediaStore(bytes: ByteArray, fileName: String, mimeType: String): SavedMedia? {
        val storageDir = java.io.File(context.filesDir, "saved_media")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val localFile = java.io.File(storageDir, fileName)
        try {
            localFile.outputStream().use { fos ->
                fos.write(bytes)
                fos.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val path = if (mimeType.startsWith("video/")) {
                    Environment.DIRECTORY_MOVIES + "/GamerEdit"
                } else {
                    Environment.DIRECTORY_PICTURES + "/GamerEdit"
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            }
        }

        val collectionUri = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        try {
            val itemUri = resolver.insert(collectionUri, contentValues)
            if (itemUri != null) {
                resolver.openOutputStream(itemUri)?.use { outputStream ->
                    outputStream.write(bytes)
                    outputStream.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val checksum = computeSha256(bytes)
            val savedMedia = SavedMedia(
                fileName = fileName,
                localUri = android.net.Uri.fromFile(localFile).toString(),
                size = bytes.size.toLong(),
                mimeType = mimeType,
                isSynced = false,
                checksum = checksum
            )

            val id = repository.insertMedia(savedMedia)
            return savedMedia.copy(id = id.toInt())
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun detectMimeFromBase64(base64: String): String? {
        if (base64.startsWith("data:image/png")) return "image/png"
        if (base64.startsWith("data:image/jpeg") || base64.startsWith("data:image/jpg")) return "image/jpeg"
        if (base64.startsWith("data:image/webp")) return "image/webp"
        if (base64.startsWith("data:image/gif")) return "image/gif"
        if (base64.startsWith("data:video/mp4")) return "video/mp4"
        return null
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
