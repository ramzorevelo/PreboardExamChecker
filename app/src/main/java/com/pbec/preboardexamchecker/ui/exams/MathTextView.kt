package com.pbec.preboardexamchecker.ui.exams

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pbec.preboardexamchecker.utils.MathEquationConverter

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathTextView(
    text: String,
    modifier: Modifier = Modifier
) {
    val containsMath = remember(text) {
        MathEquationConverter.containsMathSyntax(text) || MathEquationConverter.isChemicalFormula(text)
    }

    if (!containsMath) {
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
        )
        return
    }

    val latex = remember(text) {
        MathEquationConverter.convertEquationTextToLaTeX(text)
    }

    AndroidView(
        modifier = modifier.heightIn(min = 32.dp), // Ensure visibility and reasonable height
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                
                // Disable scrolling to keep it looking like a label
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val escapedLatex = latex.replace("\\", "\\\\").replace("'", "\\'")
                        // Inject CSS to increase font size to match bodyLarge (~16sp)
                        view?.evaluateJavascript("""
                            (function() {
                                var style = document.createElement('style');
                                style.innerHTML = 'body { font-size: 16px !important; } .katex { font-size: 1.1em !important; max-height: none !important; }';
                                document.head.appendChild(style);
                                window.androidCallback.render('$escapedLatex', false);
                            })()
                        """.trimIndent(), null)
                    }
                }
                
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d("MathTextView", consoleMessage?.message() ?: "")
                        return true
                    }
                }
                
                loadUrl("file:///android_asset/katex/math_template.html")
            }
        },
        update = { webView ->
            val escapedLatex = latex.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript("window.androidCallback.render('$escapedLatex', false)", null)
        }
    )
}
