package fr.releveimmo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val permissionCode = 43
    private val saveCode = 44
    private var pendingBytes: ByteArray? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private val restart = Runnable { if (listening) startSession() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        val bridge = Bridge()
        web.addJavascriptInterface(bridge, "AndroidSpeech")
        web.addJavascriptInterface(bridge, "AndroidFiles")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class Bridge {
        @JavascriptInterface fun start() {
            runOnUiThread {
                if (listening) return@runOnUiThread
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionCode)
                else beginDictation()
            }
        }
        @JavascriptInterface fun stop() { runOnUiThread { stopDictation() } }
        @JavascriptInterface fun save(filename: String, mimeType: String, base64Data: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                runOnUiThread {
                    pendingBytes = bytes
                    val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100).ifBlank { "export.xml" }
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mimeType.substringBefore(';').ifBlank { "application/octet-stream" }
                        putExtra(Intent.EXTRA_TITLE, safeName)
                    }
                    try { startActivityForResult(intent, saveCode) }
                    catch (_: Exception) { pendingBytes = null; sendFileStatus("Enregistrement indisponible") }
                }
            } catch (_: Exception) { runOnUiThread { sendFileStatus("Fichier invalide") } }
        }
    }

    private fun beginDictation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendState(false, "Reconnaissance vocale indisponible sur ce téléphone")
            return
        }
        listening = true
        sendState(true, "Écoute en cours…")
        startSession()
    }

    private fun startSession() {
        if (!listening) return
        handler.removeCallbacks(restart)
        recognizer?.destroy()
        recognizer = null
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (listening) sendState(true, "Écoute en cours…") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (listening) sendState(true, "Analyse de la phrase…") }
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (listening) web.evaluateJavascript("window.releveNativeSpeechPartial && window.releveNativeSpeechPartial(${org.json.JSONObject.quote(t)});", null)
                }
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (listening && t.isNotBlank()) sendSpeech(t, "Phrase ajoutée. Écoute relancée…")
                    scheduleRestart()
                }
                override fun onError(error: Int) {
                    if (!listening) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopDictation("Autorisation du microphone refusée")
                    } else {
                        scheduleRestart()
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            sr.startListening(intent)
        } catch (_: Exception) { stopDictation("Impossible de démarrer la reconnaissance vocale") }
    }

    private fun scheduleRestart() {
        if (!listening) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, 450L)
    }

    private fun stopDictation(message: String = "Dictée arrêtée") {
        listening = false
        handler.removeCallbacks(restart)
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        sendState(false, message)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginDictation()
            else sendState(false, "Autorisation du microphone refusée")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == saveCode) {
            val bytes = pendingBytes
            pendingBytes = null
            if (resultCode == RESULT_OK && data?.data != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Impossible d'ouvrir le fichier")
                    sendFileStatus("Fichier enregistré")
                } catch (_: Exception) { sendFileStatus("Échec de l'enregistrement") }
            } else sendFileStatus("Enregistrement annulé")
        }
    }

    private fun sendSpeech(text: String, status: String) {
        web.evaluateJavascript("window.releveNativeSpeechResult && window.releveNativeSpeechResult(${org.json.JSONObject.quote(text)},${org.json.JSONObject.quote(status)});", null)
    }
    private fun sendState(active: Boolean, status: String) {
        web.evaluateJavascript("window.releveNativeSpeechState && window.releveNativeSpeechState($active,${org.json.JSONObject.quote(status)});", null)
    }
    private fun sendFileStatus(status: String) {
        web.evaluateJavascript("window.releveNativeFileStatus && window.releveNativeFileStatus(${org.json.JSONObject.quote(status)});", null)
    }
    override fun onDestroy() {
        stopDictation()
        web.destroy()
        super.onDestroy()
    }
}
