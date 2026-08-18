package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

internal class FolderPageSource(
    private val context: Context,
    private val treeUri: Uri,
) : CachedDebugComicPageSource() {
    private val contentResolver: ContentResolver = context.contentResolver
    private var pages = emptyList<DocumentFile>()
    private var selectedFolderName: String? = null
    override var diagnostics = DebugSourceDiagnostics("FOLDER", treeUri.toString())
        private set

    override val pageCount: Int get() = pages.size
    override val statusMessage: String
        get() = "Found $pageCount image pages" + (selectedFolderName?.let { " in $it" } ?: "")

    override suspend fun initialize() =
        withContext(Dispatchers.IO) {
            Timber.i("PageCurl folder selected uri=%s", treeUri)
            val root =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: error("The selected folder could not be opened")
            val children = root.listFiles().toList()
            logChildren("selected folder", children)
            val directPages = children.filter(::isSupportedPage)
            pages =
                if (directPages.isNotEmpty()) {
                    diagnostics =
                        diagnostics.copy(
                            selectedName = root.name,
                            topLevelChildren = children.map { it.name.orEmpty() },
                            imageCandidates = directPages.map { it.name.orEmpty() },
                            discoveryLocation = "directly in selected folder",
                        )
                    directPages.sortedWith(PAGE_COMPARATOR)
                } else {
                    val directories = children.filter(DocumentFile::isDirectory)
                    val candidates =
                        directories.mapNotNull { directory ->
                            val nested = directory.listFiles().toList()
                            logChildren("child ${directory.name}", nested)
                            nested
                                .filter(::isSupportedPage)
                                .sortedWith(PAGE_COMPARATOR)
                                .takeIf(List<DocumentFile>::isNotEmpty)
                                ?.let { directory to it }
                        }
                    diagnostics =
                        diagnostics.copy(
                            selectedName = root.name,
                            topLevelChildren = children.map { it.name.orEmpty() },
                            imageCandidates = candidates.flatMap { it.second }.map { it.name.orEmpty() },
                            nestedCandidateFolders = candidates.map { it.first.name.orEmpty() },
                        )
                    when (candidates.size) {
                        1 -> {
                            selectedFolderName = candidates.single().first.name
                            Timber.i("PageCurl selected nested chapter folder=%s", selectedFolderName)
                            candidates.single().second
                        }
                        0 -> error("No supported page images were found in this folder or its child folders")
                        else -> {
                            val names = candidates.joinToString { it.first.name ?: "unnamed" }
                            error("Multiple chapter folders contain images; select one directly: $names")
                        }
                    }
                }
            Timber.i("PageCurl folder supported images=%d", pages.size)
            diagnostics =
                diagnostics.copy(
                    detectedPageCount = pages.size,
                    firstPageNames = pages.take(5).map { it.name.orEmpty() },
                    discoveryLocation = selectedFolderName?.let { "nested child: $it" } ?: diagnostics.discoveryLocation,
                )
        }

    override suspend fun decodePage(index: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val page = pages.getOrNull(index) ?: return@withContext null
            contentResolver.openInputStream(page.uri)?.use(BitmapFactory::decodeStream)
        }

    private fun isSupportedPage(file: DocumentFile): Boolean {
        if (!file.isFile) return false
        val name = file.name.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        if (name.startsWith('.') || name.equals(".nomedia", true) || name.equals("ComicInfo.xml", true)) return false
        if (extension == "avif") {
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (!supported) Timber.w("PageCurl AVIF unsupported on Android %d: %s", Build.VERSION.SDK_INT, name)
            return supported
        }
        return isDebugComicImage(name, file.type)
    }

    private fun logChildren(
        label: String,
        children: List<DocumentFile>,
    ) {
        children.forEach { child ->
            val name = child.name.orEmpty()
            Timber.i(
                "PageCurl %s child name=%s mime=%s directory=%s extension=%s",
                label,
                name,
                child.type,
                child.isDirectory,
                name.substringAfterLast('.', ""),
            )
        }
    }

    private companion object {
        val PAGE_COMPARATOR =
            Comparator<DocumentFile> { left, right ->
                DebugNaturalFilenameComparator.compare(left.name.orEmpty(), right.name.orEmpty())
            }
    }
}
