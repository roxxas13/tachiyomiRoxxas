package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.zip.ZipInputStream

internal class CbzPageSource(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
) : CachedDebugComicPageSource() {
    private var entries = emptyList<String>()
    override var diagnostics = DebugSourceDiagnostics("CBZ", uri.toString())
        private set

    override val pageCount: Int get() = entries.size
    override val statusMessage: String get() = "Found $pageCount image pages"

    override suspend fun initialize() =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName()
            val mimeType = contentResolver.getType(uri)
            Timber.i("PageCurl CBZ selected uri=%s name=%s mime=%s", uri, displayName, mimeType)
            val signatureValid = hasZipSignature()
            diagnostics = diagnostics.copy(selectedName = displayName, zipSignatureValid = signatureValid)
            if (!signatureValid) error("Selected file does not have a ZIP signature")
            val discoveredEntries = mutableListOf<String>()
            entries =
                contentResolver
                    .openInputStream(uri)
                    ?.use { input ->
                        ZipInputStream(input.buffered()).use { zip ->
                            buildList {
                                var entry = zip.nextEntry
                                while (entry != null) {
                                    discoveredEntries += entry.name
                                    val extension = entry.name.substringAfterLast('.', "")
                                    val supported = !entry.isDirectory && isDebugComicImage(entry.name)
                                    Timber.d(
                                        "PageCurl CBZ entry name=%s directory=%s extension=%s supported=%s",
                                        entry.name,
                                        entry.isDirectory,
                                        extension,
                                        supported,
                                    )
                                    if (supported) add(entry.name)
                                    zip.closeEntry()
                                    entry = zip.nextEntry
                                }
                            }
                        }
                    }?.sortedWith(DebugNaturalFilenameComparator)
                    .orEmpty()
            Timber.i("PageCurl CBZ supported images=%d", entries.size)
            diagnostics =
                DebugSourceDiagnostics(
                    sourceType = "CBZ",
                    selectedUri = uri.toString(),
                    selectedName = displayName,
                    topLevelChildren = discoveredEntries.take(20),
                    imageCandidates = entries,
                    detectedPageCount = entries.size,
                    firstPageNames = entries.take(5),
                    discoveryLocation = "ZIP entries",
                    zipSignatureValid = signatureValid,
                )
            if (entries.isEmpty()) error("ZIP contains no supported JPG, PNG, or WebP entries")
        }

    private fun hasZipSignature(): Boolean {
        val signature = ByteArray(4)
        val count = contentResolver.openInputStream(uri)?.use { it.read(signature) } ?: 0
        val valid = count == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
        return valid
    }

    private fun queryDisplayName(): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        }

    override suspend fun decodePage(index: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val wanted = entries.getOrNull(index) ?: return@withContext null
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == wanted) return@use BitmapFactory.decodeStream(zip)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    null
                }
            }
        }
}
