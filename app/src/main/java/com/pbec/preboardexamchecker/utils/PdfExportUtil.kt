package com.pbec.preboardexamchecker.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.pbec.preboardexamchecker.BuildConfig
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.image.ImageData
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.LineSeparator
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.properties.VerticalAlignment
import com.pbec.preboardexamchecker.data.models.Question
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

class PdfExportUtil(private val context: Context) {

    private lateinit var document: Document
    private lateinit var pdfDocument: PdfDocument
    private lateinit var pdfWriter: PdfWriter
    private lateinit var boldDocumentFont: PdfFont
    private lateinit var normalDocumentFont: PdfFont
    private var sharedWebView: WebView? = null
    private val defaultFontSize = 12f
    private val textLineSpacing = defaultFontSize * 1.2f
    private val equationLineSpacing = defaultFontSize * 1.5f
    private val targetEquationHeight = textLineSpacing * 1.5f
    private val minCellWidth = 10f
    private val equationSpacing = defaultFontSize * 0.2f

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView? {
        return try {
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.setBackgroundColor(Color.WHITE)
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            Log.d("PdfExportUtil", "WebView created successfully")
            webView
        } catch (e: Exception) {
            Log.e("PdfExportUtil", "Failed to create WebView: ${e.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.loadWithOverviewMode = false
            webView.settings.useWideViewPort = false
            webView.settings.builtInZoomControls = false
            webView.settings.displayZoomControls = false
            webView.settings.setSupportZoom(false)
            webView.settings.allowFileAccess = true
            webView.settings.allowUniversalAccessFromFileURLs = true
            webView.setBackgroundColor(Color.WHITE)
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            WebView.setWebContentsDebuggingEnabled(true)
            Log.d("PdfExportUtil", "WebView setup completed")
        } catch (e: Exception) {
            Log.e("PdfExportUtil", "Failed to setup WebView: ${e.message}")
        }
    }

    private fun loadHtmlTemplate(context: Context): String {
        return try {
            context.assets.open("katex/math_template.html").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e("PdfExportUtil", "Failed to load math_template.html: ${e.message}")
            ""
        }
    }

    private fun loadKaTeXCss(context: Context): String {
        return try {
            context.assets.open("katex/katex.min.css").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e("PdfExportUtil", "Failed to load katex.min.css: ${e.message}")
            ""
        }
    }

    private fun loadKaTeXJs(context: Context): String {
        return try {
            context.assets.open("katex/katex.min.js").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e("PdfExportUtil", "Failed to load katex.min.js: ${e.message}")
            ""
        }
    }

    private fun validateAssets(context: Context): Boolean {
        try {
            val html = loadHtmlTemplate(context)
            val css = loadKaTeXCss(context)
            val js = loadKaTeXJs(context)
            Log.d("PdfExportUtil", "Asset validation: HTML=${html.length}, CSS=${css.length}, JS=${js.length}")
            if (html.isEmpty() || css.isEmpty() || js.isEmpty()) {
                Log.e("PdfExportUtil", "Asset validation failed: Empty asset detected")
                return false
            }
            if (!html.contains("window.androidCallback")) {
                Log.e("PdfExportUtil", "Asset validation failed: window.androidCallback not found in math_template.html")
                return false
            }
            return true
        } catch (e: IOException) {
            Log.e("PdfExportUtil", "Asset validation failed: ${e.message}")
            return false
        }
    }

    private suspend fun WebView.awaitPageLoad() {
        val cssContent = loadKaTeXCss(context)
        val jsContent = loadKaTeXJs(context)
        val finalHtml = loadHtmlTemplate(context)
            .replace("<link rel=\"stylesheet\" href=\"katex.min.css\">", "<style>$cssContent</style>")
            .replace("<script defer src=\"katex.min.js\"></script>", "<script>$jsContent</script>")

        return suspendCancellableCoroutine { continuation ->
            val client = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d("PdfExportUtil", "WebView page finished loading from data")
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e("PdfExportUtil", "WebView error: ${error?.description} (Code: ${error?.errorCode})")
                    if (continuation.isActive) continuation.resumeWithException(RuntimeException("WebView error: ${error?.description}"))
                }
            }
            this.webViewClient = client
            Log.d("PdfExportUtil", "Loading WebView with combined HTML data")
            this.loadDataWithBaseURL("file:///android_asset/katex/", finalHtml, "text/html", "UTF-8", null)
            continuation.invokeOnCancellation {
                CoroutineScope(Dispatchers.Main).launch {
                    this@awaitPageLoad.stopLoading()
                    this@awaitPageLoad.loadUrl("about:blank")
                    Log.d("PdfExportUtil", "WebView page load cancelled")
                }
            }
        }
    }

    private suspend fun renderLaTeXToBitmap(
        latexString: String,
        originalText: String,
        displayMode: Boolean = false,
        availableWidth: Float = 400f
    ): Bitmap {
        return withContext(Dispatchers.Main) {
            Log.d("PdfExportUtil", "Starting renderLaTeXToBitmap for: $latexString, displayMode=$displayMode")
            val webView = sharedWebView ?: run {
                Log.e("PdfExportUtil", "WebView not available for LaTeX: $latexString, using fallback")
                return@withContext createFallbackBitmap(originalText, targetEquationHeight)
            }

            if (latexString.isBlank() || MathEquationConverter.isUnderscoreOnly(latexString)) {
                Log.w("PdfExportUtil", "Empty or underscore-only LaTeX string, using fallback")
                return@withContext createFallbackBitmap(originalText, targetEquationHeight)
            }

            try {
                val bitmap = withTimeoutOrNull(5_000L) {
                    suspendCancellableCoroutine<Bitmap> { continuation ->
                        val onRenderError: (Exception) -> Unit = { exception ->
                            if (continuation.isActive) {
                                Log.e("PdfExportUtil", "Error rendering LaTeX '$latexString': ${exception.message}")
                                continuation.resumeWithException(exception)
                            }
                        }

                        var retryCount = 0
                        val maxRetries = 2

                        webView.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                if (BuildConfig.DEBUG) {
                                    Log.d("WebViewConsole", "${consoleMessage.message()} -- From ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                                }
                                when {
                                    consoleMessage.message().startsWith("js-callback://renderComplete") -> {
                                        val uri = consoleMessage.message().toUri()
                                        val width = uri.getQueryParameter("width")?.toFloatOrNull() ?: 0f
                                        var height = uri.getQueryParameter("height")?.toFloatOrNull() ?: 0f

                                        if (width <= 0 || height <= 0) {
                                            if (retryCount < maxRetries) {
                                                retryCount++
                                                Log.w("PdfExportUtil", "Invalid dimensions (W=$width, H=$height), retrying ($retryCount/$maxRetries)")
                                                val escapedLatexString = latexString.replace("\\", "\\\\").replace("'", "\\'")
                                                webView.evaluateJavascript("""
                                                    try {
                                                        console.log('JS: Retrying render "$escapedLatexString"');
                                                        window.androidCallback.render('$escapedLatexString', $displayMode);
                                                    } catch (e) {
                                                        console.error('JS: Retry error: ' + e.message);
                                                        window.location.href = 'js-callback://renderError?message=' + encodeURIComponent('JS_EVAL_ERROR: ' + e.message);
                                                    }
                                                """.trimIndent(), null)
                                                return true
                                            } else {
                                                onRenderError(RuntimeException("Invalid dimensions: W=$width, H=$height for LaTeX: $latexString"))
                                                return true
                                            }
                                        }

                                        val aspectRatio = width / height
                                        val finalHeight = (targetEquationHeight * 0.8f).coerceAtMost(16f)
                                        val finalWidth = (finalHeight * aspectRatio).coerceAtMost(availableWidth * 0.8f)
                                        val scaleFactor = min(finalWidth / width, finalHeight / height) * 0.9f // Reduced scale for better fit

                                        val bitmapWidthPx = (width * scaleFactor).toInt().coerceAtLeast(10)
                                        val bitmapHeightPx = (height * scaleFactor).toInt().coerceAtLeast(10)

                                        // Extremely small rendered equations often appear as black blobs in PDF.
                                        if (bitmapWidthPx < 18 || bitmapHeightPx < 12) {
                                            Log.w(
                                                "PdfExportUtil",
                                                "Rendered equation too small (W=$bitmapWidthPx, H=$bitmapHeightPx), using text fallback for '$originalText'"
                                            )
                                            if (continuation.isActive) {
                                                continuation.resume(createFallbackBitmap(originalText, targetEquationHeight))
                                            }
                                            return true
                                        }

                                        if (!displayMode && finalWidth > availableWidth * 0.8f) {
                                            Log.w("PdfExportUtil", "Width $finalWidth exceeds available $availableWidth, retrying as display mode")
                                            continuation.cancel()
                                            CoroutineScope(Dispatchers.Main).launch {
                                                val displayBitmap = renderLaTeXToBitmap(latexString, originalText, displayMode = true, availableWidth)
                                                if (continuation.isActive) continuation.resume(displayBitmap)
                                            }
                                            return true
                                        }

                                        webView.evaluateJavascript("""
                                            document.getElementById('latexOutput').style.transform = 'scale(${scaleFactor})';
                                            document.getElementById('latexOutput').style.transformOrigin = '0 0';
                                        """.trimIndent(), null)

                                        webView.measure(
                                            View.MeasureSpec.makeMeasureSpec(bitmapWidthPx, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(bitmapHeightPx, View.MeasureSpec.EXACTLY)
                                        )
                                        webView.layout(0, 0, bitmapWidthPx, bitmapHeightPx)

                                        val bitmap = createBitmap(bitmapWidthPx, bitmapHeightPx, Bitmap.Config.ARGB_8888)
                                        val canvas = Canvas(bitmap)
                                        canvas.drawColor(Color.WHITE)
                                        webView.draw(canvas)

                                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmapWidthPx, bitmapHeightPx, true)
                                        bitmap.recycle()
                                        Log.d("PdfExportUtil", "Rendered bitmap: W=$bitmapWidthPx, H=$bitmapHeightPx")
                                        if (continuation.isActive) continuation.resume(scaledBitmap)
                                        return true
                                    }
                                    consoleMessage.message().startsWith("js-callback://renderError") -> {
                                        val uri = consoleMessage.message().toUri()
                                        val errorMessage = uri.getQueryParameter("message") ?: "Unknown KaTeX error"
                                        onRenderError(RuntimeException("KaTeX failed: $errorMessage for LaTeX: $latexString"))
                                        return true
                                    }
                                    else -> return super.onConsoleMessage(consoleMessage)
                                }
                            }
                        }

                        Log.d("PdfExportUtil", "Executing render for LaTeX: $latexString")
                        val escapedLatexString = latexString.replace("\\", "\\\\").replace("'", "\\'")
                        webView.evaluateJavascript("""
                            try {
                                console.log('JS: Rendering "$escapedLatexString"');
                                window.androidCallback.render('$escapedLatexString', $displayMode);
                            } catch (e) {
                                console.error('JS: Error: ' + e.message);
                                window.location.href = 'js-callback://renderError?message=' + encodeURIComponent('JS_EVAL_ERROR: ' + e.message);
                            }
                        """.trimIndent(), null)

                        continuation.invokeOnCancellation {
                            CoroutineScope(Dispatchers.Main).launch {
                                webView.stopLoading()
                                webView.loadUrl("about:blank")
                                Log.d("PdfExportUtil", "Cancelled rendering for LaTeX: $latexString")
                            }
                        }
                    }
                } ?: createFallbackBitmap(originalText, targetEquationHeight).also {
                    Log.e("PdfExportUtil", "Rendering timed out for LaTeX: $latexString, using fallback")
                }
                bitmap
            } catch (e: Exception) {
                Log.e("PdfExportUtil", "Error rendering LaTeX: $latexString, using fallback: ${e.message}")
                createFallbackBitmap(originalText, targetEquationHeight)
            }
        }
    }

    private fun createFallbackBitmap(text: String, targetHeight: Float): Bitmap {
        val safeText = sanitizePdfText(text)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = defaultFontSize * 0.8f
            isAntiAlias = true
        }
        val textWidth = paint.measureText(safeText.take(20))
        val bitmap = createBitmap(textWidth.toInt() + 10, targetHeight.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(safeText.take(20), 5f, targetHeight * 0.8f, paint)
        Log.d("PdfExportUtil", "Created fallback bitmap for text: $safeText")
        return bitmap
    }

    private fun estimateTextWidth(text: String, font: PdfFont, fontSize: Float): Float {
        var width = 0f
        for (char in text) width += font.getWidth(char.toString(), fontSize)
        return width
    }

    private data class OptionPart(
        val text: String? = null,
        val bitmap: Bitmap? = null,
        val imageData: ImageData? = null,
        val estimatedWidth: Float = 0f
    )

    suspend fun exportExamToPdf(outputUri: Uri, examTitle: String, subjectCluster: String, questions: List<Question>) {
        Log.d("PdfExportUtil", "Starting exportExamToPdf: $examTitle, ${questions.size} questions")

        if (examTitle.isBlank()) throw IllegalArgumentException("Exam title cannot be empty")
        if (questions.isEmpty()) throw IllegalArgumentException("Questions list cannot be empty")
        if (!validateAssets(context)) throw IllegalStateException("Asset validation failed")

        try {
            withContext(Dispatchers.Main) {
                sharedWebView = createWebView(context)?.also { wv ->
                    setupWebView(wv)
                    wv.awaitPageLoad()
                }
            }
            if (sharedWebView == null) throw IllegalStateException("Failed to initialize WebView")

            withContext(Dispatchers.IO) {
                val longBondPageSize = PageSize(8.5f * 72f, 13f * 72f)
                pdfWriter = PdfWriter(context.contentResolver.openOutputStream(outputUri) ?: throw IOException("Failed to open output stream for $outputUri"))
                pdfDocument = PdfDocument(pdfWriter)
                document = Document(pdfDocument, longBondPageSize)

                boldDocumentFont = createPreferredPdfFont(
                    candidates = listOf(
                        "/system/fonts/NotoSans-Bold.ttf",
                        "/system/fonts/Roboto-Bold.ttf",
                        "/system/fonts/DroidSans-Bold.ttf"
                    ),
                    fallback = StandardFonts.HELVETICA_BOLD
                )
                normalDocumentFont = createPreferredPdfFont(
                    candidates = listOf(
                        "/system/fonts/NotoSans-Regular.ttf",
                        "/system/fonts/Roboto-Regular.ttf",
                        "/system/fonts/DroidSans.ttf"
                    ),
                    fallback = StandardFonts.HELVETICA
                )

                document.add(
                    Paragraph(resolveClusterHeader(subjectCluster))
                        .setTextAlignment(TextAlignment.CENTER)
                        .setFont(boldDocumentFont)
                        .setFontSize(16f)
                        .setMarginBottom(8f)
                )
                document.add(
                    LineSeparator(SolidLine(0.8f))
                        .setMarginBottom(8f)
                )
                document.add(
                    Paragraph()
                        .add(Text("Instruction: ").setFont(boldDocumentFont))
                        .add(
                            Text(
                                "Select the correct answer for each of the following questions. " +
                                    "Mark only one answer for each item by shading the box corresponding to the letter " +
                                    "of your choice on the answer sheet provided."
                            ).setFont(normalDocumentFont)
                        )
                        .setTextAlignment(TextAlignment.JUSTIFIED)
                        .setFontSize(12f)
                        .setMarginBottom(8f)
                )
                document.add(
                    LineSeparator(SolidLine(0.8f))
                        .setMarginBottom(6f)
                )
                document.add(
                    Paragraph("Multiple Choice")
                        .setFont(normalDocumentFont)
                        .setItalic()
                        .setFontSize(12f)
                        .setMarginBottom(12f)
                )

                val pageEffectiveWidth = document.getPageEffectiveArea(longBondPageSize).width

                for (index in questions.indices) {
                    val question = questions[index]
                    if (question.questionText.isBlank()) {
                        Log.w("PdfExportUtil", "Skipping empty question at index $index")
                        continue
                    }

                    val questionLayoutTable = Table(UnitValue.createPercentArray(floatArrayOf(5f, 95f)))
                    questionLayoutTable.setWidth(UnitValue.createPercentValue(100f))
                    questionLayoutTable.setMarginBottom(0f)

                    val questionNumberCell = Cell().add(
                        Paragraph("${index + 1}.")
                            .setFont(boldDocumentFont)
                            .setFontSize(defaultFontSize)
                            .setMargin(0f)
                            .setFixedLeading(textLineSpacing)
                    ).setBorder(null).setTextAlignment(TextAlignment.LEFT).setVerticalAlignment(VerticalAlignment.TOP)
                    questionLayoutTable.addCell(questionNumberCell)

                    val questionContentCell = Cell().setBorder(null)
                    val originalQuestionText = sanitizePdfText(question.questionText)
                    val questionIsCurrency = MathEquationConverter.isCurrencyValue(originalQuestionText)
                    val questionHasMath = MathEquationConverter.containsMathSyntax(originalQuestionText) || MathEquationConverter.isChemicalFormula(originalQuestionText)

                    val questionTextParagraph = Paragraph()
                        .setMargin(0f)
                        .setFontSize(defaultFontSize)

                    if (questionIsCurrency || !questionHasMath) {
                        questionTextParagraph.add(Text(originalQuestionText).setFont(normalDocumentFont))
                        questionTextParagraph.setFixedLeading(textLineSpacing)
                    } else {
                        val parts = MathEquationConverter.splitMathParts(originalQuestionText)
                        var hasEquationInParagraph = false
                        for ((part, isMath) in parts) {
                            if (!isMath || MathEquationConverter.getExcludedFractionUnits().contains(part.lowercase().replace(Regex("[^a-z0-9/]"), ""))) {
                                if (part.isNotEmpty()) questionTextParagraph.add(Text(part).setFont(normalDocumentFont))
                            } else {
                                try {
                                    val simpleMathText = convertSimpleMathToText(part)
                                    if (simpleMathText != null) {
                                        questionTextParagraph.add(Text(simpleMathText).setFont(normalDocumentFont))
                                        continue
                                    }
                                    val convertedPart = MathEquationConverter.convertEquationTextToLaTeX(part)
                                    if (MathEquationConverter.containsMathSyntax(part) || MathEquationConverter.isChemicalFormula(part)) {
                                        val bitmap = renderLaTeXToBitmap(convertedPart, part, displayMode = false, availableWidth = pageEffectiveWidth * 0.95f)
                                        val imageData = ImageDataFactory.create(bitmap.toByteArray())
                                        val image = Image(imageData)
                                        image.scaleToFit(bitmap.width.toFloat() * 0.9f, targetEquationHeight)
                                        image.setMarginLeft(equationSpacing)
                                        image.setMarginRight(equationSpacing)
                                        questionTextParagraph.add(image)
                                        bitmap.recycle()
                                        hasEquationInParagraph = true
                                    } else {
                                        questionTextParagraph.add(Text(part).setFont(normalDocumentFont))
                                    }
                                } catch (e: Exception) {
                                    Log.e("PdfExportUtil", "Error rendering LaTeX '$part': ${e.message}")
                                    questionTextParagraph.add(Text(part).setFont(normalDocumentFont))
                                }
                            }
                        }
                        questionTextParagraph.setFixedLeading(if (hasEquationInParagraph) equationLineSpacing else textLineSpacing)
                    }
                    questionContentCell.add(questionTextParagraph)

                    val options = listOf(question.optionA, question.optionB, question.optionC, question.optionD)
                        .map { sanitizePdfText(it) }
                        .filter { it.isNotBlank() }
                    val prefixes = listOf("A. ", "B. ", "C. ", "D. ").take(options.size)
                    val renderedOptionParts = mutableListOf<Pair<MutableList<OptionPart>, String>>()
                    val optionPrefixWidth = estimateTextWidth(prefixes[0], normalDocumentFont, defaultFontSize)
                    val estimatedOptionWidths = mutableListOf<Float>()

                    for (j in options.indices) {
                        val optionText = options[j]
                        val optionIsCurrency = MathEquationConverter.isCurrencyValue(optionText)
                        val optionHasMath = MathEquationConverter.containsMathSyntax(optionText) || MathEquationConverter.isChemicalFormula(optionText)
                        val convertedOptionText = if (optionIsCurrency || !optionHasMath) optionText else {
                            try {
                                MathEquationConverter.convertEquationTextToLaTeX(optionText)
                            } catch (e: Exception) {
                                Log.e("PdfExportUtil", "Error converting option text: $optionText, error: ${e.message}")
                                optionText
                            }
                        }

                        val currentOptionParts = mutableListOf<OptionPart>()
                        var estimatedOptionWidth = optionPrefixWidth
                        val optionTextParts = if (optionIsCurrency || !optionHasMath) listOf(Pair(optionText, false)) else MathEquationConverter.splitMathParts(optionText)

                        for ((part, isMath) in optionTextParts) {
                            if (!isMath || MathEquationConverter.getExcludedFractionUnits().contains(part.lowercase().replace(Regex("[^a-z0-9/]"), ""))) {
                                if (part.isNotEmpty()) {
                                    currentOptionParts.add(OptionPart(text = part, estimatedWidth = estimateTextWidth(part, normalDocumentFont, defaultFontSize)))
                                    estimatedOptionWidth += estimateTextWidth(part, normalDocumentFont, defaultFontSize)
                                }
                            } else {
                                // Do not render option math as bitmaps. Tiny raster outputs become black artifacts.
                                val textFallback = convertSimpleMathToText(part) ?: sanitizePdfText(part)
                                currentOptionParts.add(
                                    OptionPart(
                                        text = textFallback,
                                        estimatedWidth = estimateTextWidth(textFallback, normalDocumentFont, defaultFontSize)
                                    )
                                )
                                estimatedOptionWidth += estimateTextWidth(textFallback, normalDocumentFont, defaultFontSize)
                            }
                        }
                        renderedOptionParts.add(Pair(currentOptionParts, convertedOptionText))
                        estimatedOptionWidths.add(estimatedOptionWidth.coerceAtLeast(minCellWidth))
                    }

                    val horizontalPaddingPerCell = 4f
                    val contentCellWidth = pageEffectiveWidth * 0.95f
                    val maxFourColumnOptionDisplayWidth = (contentCellWidth / 4f) - (2f * horizontalPaddingPerCell)
                    val maxTwoColumnOptionDisplayWidth = (contentCellWidth / 2f) - (2f * horizontalPaddingPerCell)
                    val maxOneColumnOptionDisplayWidth = contentCellWidth - (2f * horizontalPaddingPerCell)

                    var useFourColumns = true
                    var useTwoColumns = true
                    for (width in estimatedOptionWidths) {
                        if (width > maxFourColumnOptionDisplayWidth) useFourColumns = false
                        if (width > maxTwoColumnOptionDisplayWidth) useTwoColumns = false
                    }

                    val optionsTable: Table
                    val columnWidths: FloatArray

                    when {
                        useFourColumns -> {
                            columnWidths = floatArrayOf(25f, 25f, 25f, 25f)
                            optionsTable = Table(UnitValue.createPercentArray(columnWidths)).useAllAvailableWidth()
                            for (j in options.indices) {
                                val (parts, convertedText) = renderedOptionParts[j]
                                val optionParagraph = Paragraph()
                                    .setMargin(0f)
                                    .setFontSize(defaultFontSize)
                                    .setFixedLeading(if (MathEquationConverter.containsMathSyntax(convertedText) || MathEquationConverter.isChemicalFormula(convertedText)) equationLineSpacing else textLineSpacing)
                                optionParagraph.add(Text(prefixes[j]).setFont(normalDocumentFont))
                                for (part in parts) {
                                    if (part.text != null) {
                                        optionParagraph.add(Text(part.text).setFont(normalDocumentFont))
                                    } else if (part.bitmap != null) {
                                        val image = Image(part.imageData)
                                        image.scaleToFit(maxFourColumnOptionDisplayWidth, targetEquationHeight)
                                        image.setAutoScaleWidth(true)
                                        image.setMarginLeft(equationSpacing)
                                        image.setMarginRight(equationSpacing)
                                        optionParagraph.add(image)
                                        part.bitmap.recycle()
                                    }
                                }
                                optionsTable.addCell(
                                    Cell().add(optionParagraph)
                                        .setPadding(horizontalPaddingPerCell)
                                        .setBorder(null)
                                        .setVerticalAlignment(VerticalAlignment.TOP)
                                        .setMinWidth(minCellWidth)
                                )
                            }
                        }
                        useTwoColumns -> {
                            columnWidths = floatArrayOf(50f, 50f)
                            optionsTable = Table(UnitValue.createPercentArray(columnWidths)).useAllAvailableWidth()
                            for (j in 0 until options.size step 2) {
                                for (k in 0..1) {
                                    if (j + k >= options.size) break
                                    val (parts, convertedText) = renderedOptionParts[j + k]
                                    val optionParagraph = Paragraph()
                                        .setMargin(0f)
                                        .setFontSize(defaultFontSize)
                                        .setFixedLeading(if (MathEquationConverter.containsMathSyntax(convertedText) || MathEquationConverter.isChemicalFormula(convertedText)) equationLineSpacing else textLineSpacing)
                                    optionParagraph.add(Text(prefixes[j + k]).setFont(normalDocumentFont))
                                    for (part in parts) {
                                        if (part.text != null) {
                                            optionParagraph.add(Text(part.text).setFont(normalDocumentFont))
                                        } else if (part.bitmap != null) {
                                            val image = Image(part.imageData)
                                            image.scaleToFit(maxTwoColumnOptionDisplayWidth, targetEquationHeight)
                                            image.setAutoScaleWidth(true)
                                            image.setMarginLeft(equationSpacing)
                                            image.setMarginRight(equationSpacing)
                                            optionParagraph.add(image)
                                            part.bitmap.recycle()
                                        }
                                    }
                                    optionsTable.addCell(
                                        Cell().add(optionParagraph)
                                            .setPadding(horizontalPaddingPerCell)
                                            .setBorder(null)
                                            .setVerticalAlignment(VerticalAlignment.TOP)
                                            .setMinWidth(minCellWidth)
                                    )
                                }
                            }
                        }
                        else -> {
                            columnWidths = floatArrayOf(100f)
                            optionsTable = Table(UnitValue.createPercentArray(columnWidths)).useAllAvailableWidth()
                            for (j in options.indices) {
                                val (parts, convertedText) = renderedOptionParts[j]
                                val optionParagraph = Paragraph()
                                    .setMargin(0f)
                                    .setFontSize(defaultFontSize)
                                    .setFixedLeading(if (MathEquationConverter.containsMathSyntax(convertedText) || MathEquationConverter.isChemicalFormula(convertedText)) equationLineSpacing else textLineSpacing)
                                optionParagraph.add(Text(prefixes[j]).setFont(normalDocumentFont))
                                for (part in parts) {
                                    if (part.text != null) {
                                        optionParagraph.add(Text(part.text).setFont(normalDocumentFont))
                                    } else if (part.bitmap != null) {
                                        val image = Image(part.imageData)
                                        image.scaleToFit(maxOneColumnOptionDisplayWidth, targetEquationHeight)
                                        image.setAutoScaleWidth(true)
                                        image.setMarginLeft(equationSpacing)
                                        image.setMarginRight(equationSpacing)
                                        optionParagraph.add(image)
                                        part.bitmap.recycle()
                                    }
                                }
                                optionsTable.addCell(
                                    Cell().add(optionParagraph)
                                        .setPadding(horizontalPaddingPerCell)
                                        .setBorder(null)
                                        .setVerticalAlignment(VerticalAlignment.TOP)
                                        .setMinWidth(minCellWidth)
                                )
                            }
                        }
                    }

                    questionContentCell.add(optionsTable)
                    questionLayoutTable.addCell(questionContentCell)
                    document.add(questionLayoutTable)
                    Log.d("PdfExportUtil", "Completed question ${index + 1}")
                }

                document.add(
                    Paragraph("\n*** END ***")
                        .setTextAlignment(TextAlignment.CENTER)
                        .setFont(normalDocumentFont)
                        .setFontSize(12f)
                        .setMarginTop(16f)
                        .setMarginBottom(12f)
                )
                document.add(
                    Paragraph(
                        "SUBMIT THIS TEST QUESTIONS SET TOGETHER WITH THE ANSWER SHEET TO YOUR WATCHERS. " +
                            "BRINGING THE TEST QUESTIONS SET OUT OF THE ROOM WILL BE A GROUND FOR DISCIPLINARY ACTION"
                    )
                        .setTextAlignment(TextAlignment.JUSTIFIED)
                        .setFont(normalDocumentFont)
                        .setFontSize(12f)
                        .setMarginBottom(0f)
                )

                document.close()
                pdfDocument.close()
                pdfWriter.close()
                Log.d("PdfExportUtil", "PDF export completed")
            }
        } catch (e: Exception) {
            Log.e("PdfExportUtil", "Fatal error in exportExamToPdf: ${e.message}")
            throw e
        } finally {
            withContext(Dispatchers.Main) {
                sharedWebView?.stopLoading()
                sharedWebView?.destroy()
                sharedWebView = null
                Log.d("PdfExportUtil", "Shared WebView destroyed")
            }
        }
    }

    private fun Bitmap.toByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun resolveClusterHeader(subjectCluster: String): String {
        return when (subjectCluster.trim().lowercase()) {
            "mathematics" -> "MATHEMATICS"
            "esas", "engineering sciences and allied subjects" -> "ENGINEERING SCIENCES AND ALLIED SUBJECTS"
            "professional ee", "professional electrical engineering subjects", "professionla electrical engineering subjects" ->
                "PROFESSIONAL ELECTRICAL ENGINEERING SUBJECTS"
            else -> subjectCluster.uppercase()
        }
    }

    private fun createPreferredPdfFont(candidates: List<String>, fallback: String): PdfFont {
        for (path in candidates) {
            try {
                if (File(path).exists()) {
                    return PdfFontFactory.createFont(path, PdfEncodings.IDENTITY_H)
                }
            } catch (_: Exception) {
                // Try next candidate font.
            }
        }
        return PdfFontFactory.createFont(fallback)
    }

    private fun sanitizePdfText(raw: String): String {
        if (raw.isBlank()) return raw

        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return normalized
            .replace("\u00A0", " ")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("‘", "'")
            .replace("’", "'")
            .replace("–", "-")
            .replace("—", "-")
            .replace("−", "-")
            .replace("•", "*")
            .replace("…", "...")
            .replace(Regex("[\\p{Cntrl}&&[^\n\t]]"), "")
    }

    private fun convertSimpleMathToText(part: String): String? {
        val cleaned = sanitizePdfText(part.trim())

        // Keep simple ratio/fraction tokens as plain text to avoid raster artifacts (e.g., 1/L, V/I, kg/m).
        if (cleaned.matches(Regex("^[A-Za-z0-9().+\\-]+\\s*/\\s*[A-Za-z0-9().+\\-]+$"))) {
            return cleaned.replace(Regex("\\s*/\\s*"), "/")
        }

        val match = Regex("^([A-Za-z0-9()+\\-*/.]+)\\^([0-9+\\-]+)$").find(cleaned) ?: return null
        val base = match.groupValues[1]
        val exp = match.groupValues[2]
        val superscript = buildString {
            exp.forEach { ch ->
                append(
                    when (ch) {
                        '0' -> '⁰'
                        '1' -> '¹'
                        '2' -> '²'
                        '3' -> '³'
                        '4' -> '⁴'
                        '5' -> '⁵'
                        '6' -> '⁶'
                        '7' -> '⁷'
                        '8' -> '⁸'
                        '9' -> '⁹'
                        '+' -> '⁺'
                        '-' -> '⁻'
                        else -> return null
                    }
                )
            }
        }
        return base + superscript
    }
}