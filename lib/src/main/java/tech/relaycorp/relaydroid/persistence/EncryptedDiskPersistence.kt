package tech.relaycorp.relaydroid.persistence

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

internal class EncryptedDiskPersistence(
    private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO,
    private val rootFolder: String = "relaydroid"
) : Persistence {

    @Suppress("BlockingMethodInNonBlockingContext")
    @Throws(PersistenceException::class)
    override suspend fun set(location: String, data: ByteArray) {
        withContext(coroutineContext) {
            delete(location)
            try {
                buildEncryptedFile(location)
                    .openFileOutput()
                    .use { it.write(data) }
            } catch (exception: IOException) {
                throw PersistenceException("Failed to write to file at $location", exception)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Throws(PersistenceException::class)
    override suspend fun get(location: String): ByteArray? = withContext(coroutineContext) {
        try {
            buildEncryptedFile(location)
                .openFileInput()
                .use { it.readBytes() }
        } catch (exception: IOException) {
            if (buildFile(location).exists()) {
                throw PersistenceException("Failed to read file at $location", exception)
            }
            null
        }
    }

    override suspend fun delete(location: String) {
        withContext(coroutineContext) {
            buildFile(location).delete()
        }
    }

    override suspend fun deleteAll() {
        withContext(coroutineContext) {
            buildFile("").deleteRecursively()
        }
    }

    override suspend fun list(locationPrefix: String) = withContext(coroutineContext) {
        val rootFolder = buildFile("")
        rootFolder
            .walkTopDown()
            .toList()
            .let { it.subList(1, it.size) } // skip first, the root
            .map { it.absolutePath.replace(rootFolder.absolutePath + File.separator, "") }
            .filter { it.startsWith(locationPrefix) }
    }

    // Helpers

    private fun buildFile(location: String) =
        File(context.filesDir, "$rootFolder${File.separator}$location").also {
            it.parentFile?.mkdirs()
        }

    private fun buildEncryptedFile(location: String) =
        EncryptedFile.Builder(
            context,
            buildFile(location),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    private val masterKey by lazy {
        MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    companion object {
        private const val MASTER_KEY_ALIAS = "_relaydroid_master_key_"
    }
}