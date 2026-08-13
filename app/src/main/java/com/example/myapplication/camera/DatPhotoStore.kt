package com.example.myapplication.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StoredDatPhoto(
    val imageUri: String,
    val width: Int,
    val height: Int,
    val byteSize: Long
)

internal sealed interface PhotoStoreResult<out T> {
    data class Success<T>(val value: T) : PhotoStoreResult<T>
    data class Failure(val message: String) : PhotoStoreResult<Nothing>
}

internal class DatPhotoStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val captureDirectory: File
        get() = File(applicationContext.cacheDir, "images/intermittent")

    suspend fun save(
        requestId: String,
        capturedAtEpochMs: Long,
        bitmap: Bitmap
    ): PhotoStoreResult<StoredDatPhoto> = withContext(Dispatchers.IO) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext PhotoStoreResult.Failure("Decoded photo dimensions are invalid")
        }
        val directory = captureDirectory
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            return@withContext PhotoStoreResult.Failure("Could not create capture cache directory")
        }
        val safeRequestId = requestId
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(64)
            .ifBlank { "capture" }
        val file = File(directory, "capture_${safeRequestId}_$capturedAtEpochMs.png")
        try {
            val compressed = FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (!compressed || !file.isFile || file.length() <= 0L) {
                file.delete()
                return@withContext PhotoStoreResult.Failure("Saved capture file is empty")
            }
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            PhotoStoreResult.Success(
                StoredDatPhoto(uri.toString(), bitmap.width, bitmap.height, file.length())
            )
        } catch (error: Exception) {
            file.delete()
            PhotoStoreResult.Failure(error.message ?: "Photo cache write failed")
        }
    }

    suspend fun delete(imageUri: String): ArtifactDeletionOutcome = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(imageUri) }.getOrNull()
            ?: return@withContext ArtifactDeletionOutcome.Failure("Artifact URI is invalid")
        val expectedAuthority = "${applicationContext.packageName}.fileprovider"
        if (uri.scheme != "content" || uri.authority != expectedAuthority) {
            return@withContext ArtifactDeletionOutcome.Failure(
                "Artifact URI is not owned by the DAT camera gateway"
            )
        }
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "shared_images" || segments[1] != "intermittent") {
            return@withContext ArtifactDeletionOutcome.Failure(
                "Artifact URI is outside the DAT capture cache"
            )
        }
        val directory = captureDirectory.canonicalFile
        val file = File(directory, segments[2]).canonicalFile
        if (file.parentFile != directory) {
            return@withContext ArtifactDeletionOutcome.Failure("Artifact path escaped capture cache")
        }
        when {
            !file.exists() -> ArtifactDeletionOutcome.NotFound
            file.delete() -> ArtifactDeletionOutcome.Deleted
            else -> ArtifactDeletionOutcome.Failure("Artifact file could not be deleted")
        }
    }
}
