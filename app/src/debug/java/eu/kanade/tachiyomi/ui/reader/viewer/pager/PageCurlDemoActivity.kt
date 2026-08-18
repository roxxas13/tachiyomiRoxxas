package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** Debug-only, explicitly launched visual harness for [PageCurlView]. */
class PageCurlDemoActivity : AppCompatActivity() {
    private val generatedPages = mutableListOf<Bitmap>()
    private var pageSource: DebugComicPageSource? = null
    private var loadedPages = emptyMap<Int, Bitmap>()
    private var loadJob: Job? = null
    private var sourceLabel = "DEMO"
    private var pageIndex = 0
    private var spreadMode = true
    private var pairingMode = PairingMode.PAIR_FROM_FIRST
    private lateinit var curlView: PageCurlView
    private lateinit var pageStatus: TextView
    private lateinit var debugStatus: TextView
    private lateinit var sourceDiagnostics: TextView
    private lateinit var rendererSpinner: Spinner

    private val openCbz =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            installSource(CbzPageSource(contentResolver, uri), "CBZ")
        }

    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            persistReadPermission(uri)
            installSource(FolderPageSource(this, uri), "FOLDER")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        curlView = PageCurlView(this)
        curlView.debugFiniteChecks = true
        repeat(8) { index ->
            val side = if (index % 2 == 0) "LEFT" else "RIGHT"
            generatedPages += createPage(GENERATED_COLORS[index % GENERATED_COLORS.size], "$side PAGE ${index + 1}")
        }
        curlView.setDirection(
            if (intent.getBooleanExtra(EXTRA_RTL, false)) PageCurlDirection.RIGHT_TO_LEFT else PageCurlDirection.LEFT_TO_RIGHT,
        )
        curlView.onCurlCompleted = { advanceAfterCurl() }
        curlView.onCurlCancelled = { updatePageStatus("Turn cancelled") }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val controls =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
            }
        rendererSpinner = createRendererSpinner()
        controls.addView(rendererSpinner)
        controls.addView(createActionRow())
        controls.addView(createSpreadSwitch())
        controls.addView(createPairingSpinner())
        pageStatus = TextView(this)
        debugStatus = TextView(this)
        sourceDiagnostics = TextView(this)
        controls.addView(pageStatus)
        controls.addView(debugStatus)
        controls.addView(sourceDiagnostics)
        curlView.onDebugStateChanged = { state ->
            debugStatus.text =
                String.format(
                    Locale.US,
                    "%s  progress %.3f  pull %.1f\nexit %.1f  touch %.1f, %.1f",
                    state.phase,
                    state.progress,
                    state.physicalPullDistance,
                    state.exitTranslation,
                    state.touchX,
                    state.touchY,
                )
        }
        controls.addView(
            Switch(this).apply {
                text = "Geometry overlay"
                setOnCheckedChangeListener { _, checked -> curlView.debugOverlay = checked }
            },
        )
        addTuner(controls, "Radius", 4, 30, 11) { value, label ->
            curlView.curlRadiusFraction = value / 100f
            label.text = "Radius: $value%"
        }
        addTuner(controls, "Horizontal grid", 20, 72, 48) { value, label ->
            curlView.horizontalSubdivisionCount = value
            label.text = "Horizontal grid: $value"
        }
        addTuner(controls, "Vertical grid", 8, 40, 20) { value, label ->
            curlView.verticalSubdivisionCount = value
            label.text = "Vertical grid: $value"
        }
        addTuner(controls, "Shadow", 0, 100, 70) { value, label ->
            curlView.shadowStrength = value / 100f
            label.text = "Shadow: $value%"
        }
        addTuner(controls, "Backside brightness", 50, 100, 80) { value, label ->
            curlView.backsideBrightness = value / 100f
            label.text = "Backside brightness: $value%"
        }
        addTuner(controls, "Exit capture", 75, 90, 82) { value, label ->
            curlView.exitCaptureProgress = value / 100f
            label.text = "Exit capture: $value%"
        }
        root.addView(controls, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(curlView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        applyGeneratedPages()
    }

    private fun createActionRow() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                Button(this@PageCurlDemoActivity).apply {
                    text = "Load CBZ"
                    setOnClickListener {
                        openCbz.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    }
                },
            )
            addView(
                Button(this@PageCurlDemoActivity).apply {
                    text = "Load Folder"
                    setOnClickListener { openFolder.launch(null) }
                },
            )
            addView(
                Button(this@PageCurlDemoActivity).apply {
                    text = "Previous"
                    setOnClickListener {
                        pageIndex = previousStart()
                        loadAndApplyCurrentSource()
                    }
                },
            )
            addView(
                Button(this@PageCurlDemoActivity).apply {
                    text = "Next/reset"
                    setOnClickListener {
                        pageIndex = nextStart()
                        loadAndApplyCurrentSource()
                    }
                },
            )
        }

    private fun createSpreadSwitch() =
        Switch(this).apply {
            text = "Two-page book spread"
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                spreadMode = checked
                pageIndex = initialStart()
                if (checked) rendererSpinner.setSelection(PageCurlRendererMode.DYNAMIC_MESH.ordinal)
                loadAndApplyCurrentSource()
            }
        }

    private fun createPairingSpinner() =
        Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@PageCurlDemoActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("Cover first: 1, then 2|3", "Pair from first: 1|2"),
                )
            setSelection(PairingMode.PAIR_FROM_FIRST.ordinal)
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long,
                    ) {
                        pairingMode = PairingMode.entries[position]
                        pageIndex = initialStart()
                        loadAndApplyCurrentSource()
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
        }

    private fun createRendererSpinner() =
        Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@PageCurlDemoActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    PageCurlRendererMode.entries.map { it.name },
                )
            setSelection(PageCurlRendererMode.DYNAMIC_MESH.ordinal)
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (spreadMode && position != PageCurlRendererMode.DYNAMIC_MESH.ordinal) {
                            setSelection(PageCurlRendererMode.DYNAMIC_MESH.ordinal)
                        } else {
                            curlView.rendererMode = PageCurlRendererMode.entries[position]
                            curlView.reset()
                        }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                }
        }

    private fun installSource(
        candidate: DebugComicPageSource,
        label: String,
    ) {
        loadJob?.cancel()
        val job =
            lifecycleScope.launch {
                pageStatus.text = "Loading $label…"
                runCatching { candidate.initialize() }.getOrElse {
                    candidate.close()
                    renderDiagnostics(candidate.diagnostics.copy(failureReason = it.message))
                    pageStatus.text = "Could not open $label: ${it.message ?: "unknown error"}"
                    return@launch
                }
                if (candidate.pageCount < 2) {
                    candidate.close()
                    renderDiagnostics(candidate.diagnostics.copy(failureReason = "Fewer than two decodable pages"))
                    pageStatus.text = "No supported page images found"
                    return@launch
                }
                pageIndex = initialStart()
                val desired = retainedIndices(candidate.pageCount)
                val decoded = decodeIndices(candidate, desired)
                if (!hasRequiredPages(decoded, candidate.pageCount)) {
                    candidate.close()
                    renderDiagnostics(candidate.diagnostics.copy(failureReason = "Initial pages could not be decoded"))
                    pageStatus.text = "Could not decode the selected comic pages"
                    return@launch
                }
                val oldSource = pageSource
                pageSource = candidate
                sourceLabel = label
                loadedPages = decoded
                applyLoadedPages()
                updatePageStatus(candidate.statusMessage)
                renderDiagnostics(candidate.diagnostics)
                candidate.retain(desired)
                oldSource?.close()
            }
        job.invokeOnCompletion {
            if (pageSource !== candidate) candidate.close()
        }
        loadJob = job
    }

    private fun loadAndApplyCurrentSource() {
        val source = pageSource
        if (source == null) {
            applyGeneratedPages()
            return
        }
        loadJob?.cancel()
        loadJob =
            lifecycleScope.launch {
                val desired = retainedIndices(source.pageCount)
                val decoded = decodeIndices(source, desired)
                if (!hasRequiredPages(decoded, source.pageCount)) {
                    pageStatus.text = "Could not decode pages near ${pageIndex + 1}"
                    return@launch
                }
                loadedPages = decoded
                applyLoadedPages()
                source.retain(desired)
            }
    }

    private suspend fun decodeIndices(
        source: DebugComicPageSource,
        indices: Set<Int>,
    ): Map<Int, Bitmap> =
        buildMap {
            indices.forEach { index -> source.loadPage(index)?.let { put(index, it) } }
        }

    private fun applyLoadedPages() {
        applyBitmaps { index -> loadedPages[index] }
    }

    private fun applyGeneratedPages() {
        applyBitmaps { index -> generatedPages.getOrNull(index) }
        sourceDiagnostics.text = "Source type: DEMO\nDetected page count: ${generatedPages.size}"
    }

    private fun applyBitmaps(page: (Int) -> Bitmap?) {
        pageIndex = pageIndex.coerceIn(0, maximumStart())
        if (isCoverPosition()) {
            val cover = page(0) ?: return
            val next = page(1) ?: return
            curlView.setPages(cover, next)
        } else if (spreadMode) {
            curlView.setSpreadPages(page(pageIndex), page(pageIndex + 1), page(pageIndex + 2), page(pageIndex + 3))
        } else {
            val current = page(pageIndex) ?: return
            val next = page(pageIndex + 1) ?: return
            curlView.setPages(current, next)
        }
        updatePageStatus()
    }

    private fun retainedIndices(count: Int): Set<Int> {
        val start = if (isCoverPosition()) 0 else pageIndex
        val before = if (spreadMode) 2 else 1
        val after = if (spreadMode) 5 else 2
        return ((start - before)..(start + after)).filter { it in 0 until count }.toSet()
    }

    private fun hasRequiredPages(
        pages: Map<Int, Bitmap>,
        count: Int,
    ): Boolean {
        val required =
            if (isCoverPosition()) {
                setOf(0, 1)
            } else if (spreadMode) {
                (pageIndex..minOf(pageIndex + 3, count - 1)).toSet()
            } else {
                (pageIndex..minOf(pageIndex + 1, count - 1)).toSet()
            }
        return required.all(pages::containsKey)
    }

    private fun isCoverPosition() = spreadMode && pairingMode == PairingMode.COVER_FIRST && pageIndex == 0

    private fun initialStart() = 0

    private fun nextStart(): Int {
        if (!spreadMode) return (pageIndex + 1).coerceAtMost(maximumStart())
        if (isCoverPosition()) return 1.coerceAtMost(maximumStart())
        return (pageIndex + 2).coerceAtMost(maximumStart())
    }

    private fun previousStart(): Int {
        if (!spreadMode) return (pageIndex - 1).coerceAtLeast(0)
        if (pairingMode == PairingMode.COVER_FIRST && pageIndex <= 1) return 0
        return (pageIndex - 2).coerceAtLeast(initialPairStart())
    }

    private fun maximumStart(): Int {
        val count = totalPages()
        if (!spreadMode) return (count - 2).coerceAtLeast(0)
        val base = (count - 1).coerceAtLeast(0)
        val parity = initialPairStart() and 1
        return if ((base and 1) == parity) base else (base - 1).coerceAtLeast(initialPairStart())
    }

    private fun initialPairStart() = if (pairingMode == PairingMode.COVER_FIRST) 1 else 0

    private fun totalPages() = pageSource?.pageCount ?: generatedPages.size

    private fun updatePageStatus(prefix: String? = null) {
        val current =
            when {
                isCoverPosition() -> "Cover page 1"
                spreadMode -> "Pages ${pageIndex + 1}|${minOf(pageIndex + 2, totalPages())}"
                else -> "Page ${pageIndex + 1} → ${minOf(pageIndex + 2, totalPages())}"
            }
        pageStatus.text = listOfNotNull(prefix, "$sourceLabel — $current / ${totalPages()}").joinToString(" — ")
    }

    private fun advanceAfterCurl() {
        pageIndex = nextStart()
        loadAndApplyCurrentSource()
    }

    private fun renderDiagnostics(diagnostics: DebugSourceDiagnostics) {
        sourceDiagnostics.text = diagnostics.displayText()
    }

    private fun persistReadPermission(uri: android.net.Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun addTuner(
        parent: LinearLayout,
        name: String,
        min: Int,
        max: Int,
        initial: Int,
        update: (Int, TextView) -> Unit,
    ) {
        val label = TextView(this)
        val seekBar =
            SeekBar(this).apply {
                this.min = min
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            update(progress, label)
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    },
                )
            }
        update(initial, label)
        parent.addView(label)
        parent.addView(seekBar)
    }

    private fun createPage(
        color: Int,
        title: String,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(color)
        paint.color = Color.rgb(45, 45, 48)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 76f
        paint.isFakeBoldText = true
        canvas.drawText(title, bitmap.width / 2f, bitmap.height * 0.42f, paint)
        return bitmap
    }

    override fun onDestroy() {
        loadJob?.cancel()
        pageSource?.close()
        generatedPages.filterNot(Bitmap::isRecycled).forEach(Bitmap::recycle)
        generatedPages.clear()
        super.onDestroy()
    }

    private enum class PairingMode { COVER_FIRST, PAIR_FROM_FIRST }

    companion object {
        const val EXTRA_RTL = "rtl"
        val GENERATED_COLORS =
            intArrayOf(
                Color.rgb(250, 244, 224),
                Color.rgb(215, 232, 248),
                Color.rgb(234, 220, 245),
                Color.rgb(218, 241, 222),
                Color.rgb(248, 224, 215),
                Color.rgb(238, 238, 210),
            )
    }
}
