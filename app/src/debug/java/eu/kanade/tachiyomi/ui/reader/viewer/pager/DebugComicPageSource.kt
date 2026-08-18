package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.os.Build

internal interface DebugComicPageSource {
    val pageCount: Int
    val statusMessage: String
    val diagnostics: DebugSourceDiagnostics

    suspend fun initialize()

    suspend fun loadPage(index: Int): Bitmap?

    /** Recycles cached pages outside [indices]. Call only after PageCurlView has received replacements. */
    fun retain(indices: Set<Int>)

    fun close()
}

internal data class DebugSourceDiagnostics(
    val sourceType: String,
    val selectedUri: String,
    val selectedName: String? = null,
    val topLevelChildren: List<String> = emptyList(),
    val imageCandidates: List<String> = emptyList(),
    val nestedCandidateFolders: List<String> = emptyList(),
    val detectedPageCount: Int = 0,
    val firstPageNames: List<String> = emptyList(),
    val discoveryLocation: String? = null,
    val zipSignatureValid: Boolean? = null,
    val failureReason: String? = null,
) {
    fun displayText(): String =
        buildString {
            appendLine("Source type: $sourceType")
            appendLine("Selected URI: $selectedUri")
            appendLine("Selected filename: ${selectedName ?: "—"}")
            appendLine("Top-level children: ${topLevelChildren.joinToString().ifEmpty { "—" }}")
            appendLine("Image candidates: ${imageCandidates.size}")
            appendLine("Nested candidate folders: ${nestedCandidateFolders.joinToString().ifEmpty { "—" }}")
            appendLine("Detected page count: $detectedPageCount")
            appendLine("First 5 detected pages: ${firstPageNames.take(5).joinToString().ifEmpty { "—" }}")
            appendLine("Discovery: ${discoveryLocation ?: "—"}")
            appendLine("ZIP signature valid: ${zipSignatureValid?.toString() ?: "—"}")
            append("Failure reason: ${failureReason ?: "—"}")
        }
}

internal abstract class CachedDebugComicPageSource : DebugComicPageSource {
    private val cache = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)

    final override suspend fun loadPage(index: Int): Bitmap? {
        cache[index]?.let { return it }
        return decodePage(index)?.also { cache[index] = it }
    }

    final override fun retain(indices: Set<Int>) {
        val iterator = cache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in indices) {
                if (!entry.value.isRecycled) entry.value.recycle()
                iterator.remove()
            }
        }
    }

    override fun close() {
        cache.values
            .distinct()
            .filterNot(Bitmap::isRecycled)
            .forEach(Bitmap::recycle)
        cache.clear()
    }

    protected abstract suspend fun decodePage(index: Int): Bitmap?
}

internal object DebugNaturalFilenameComparator : Comparator<String> {
    override fun compare(
        left: String,
        right: String,
    ): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            if (left[leftIndex].isDigit() && right[rightIndex].isDigit()) {
                val leftStart = leftIndex
                val rightStart = rightIndex
                while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
                while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++
                val leftNumber = left.substring(leftStart, leftIndex).trimStart('0')
                val rightNumber = right.substring(rightStart, rightIndex).trimStart('0')
                leftNumber.length
                    .compareTo(rightNumber.length)
                    .takeIf { it != 0 }
                    ?.let { return it }
                leftNumber.compareTo(rightNumber, ignoreCase = true).takeIf { it != 0 }?.let { return it }
            } else {
                left[leftIndex]
                    .lowercaseChar()
                    .compareTo(right[rightIndex].lowercaseChar())
                    .takeIf { it != 0 }
                    ?.let { return it }
                leftIndex++
                rightIndex++
            }
        }
        return left.length.compareTo(right.length)
    }
}

internal fun isDebugComicImage(
    name: String,
    mimeType: String? = null,
): Boolean {
    val normalized = name.replace('\\', '/')
    if (normalized.startsWith("__MACOSX/") || normalized.split('/').any { it.startsWith('.') }) return false
    val extension = normalized.substringAfterLast('.', "").lowercase()
    if (extension in setOf("jpg", "jpeg", "png", "webp")) return true
    if (extension == "avif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val normalizedMime = mimeType?.lowercase()
    if (normalizedMime == "image/avif") return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return normalizedMime in setOf("image/jpeg", "image/png", "image/webp")
}
