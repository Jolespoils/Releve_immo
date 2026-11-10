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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val geminiRequestId = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var geminiActiveConnection: HttpURLConnection? = null
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
        web.addJavascriptInterface(bridge, "AndroidAI")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private val keyAlias = "releve_immo_gemini_key"
    private val prefs by lazy { getSharedPreferences("releve_secure", MODE_PRIVATE) }

    private fun cipherKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(keyAlias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    private fun storeApiKey(value: String) {
        if (value.isBlank()) { prefs.edit().remove("gemini").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("gemini", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    private fun loadApiKey(): String {
        val encoded = prefs.getString("gemini", null) ?: return ""
        val data = Base64.decode(encoded, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cipherKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
    private fun callback(name: String, vararg args: String) {
        runOnUiThread { web.evaluateJavascript("window.$name && window.$name(${args.joinToString(",")});", null) }
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
        @JavascriptInterface fun hasGeminiKey() {
            val exists = try { loadApiKey().isNotEmpty() } catch (_: Exception) { false }
            callback("releveGeminiKeyStatus", exists.toString())
        }
        @JavascriptInterface fun setGeminiKey(key: String) {
            val success = try { storeApiKey(key.trim()); true } catch (_: Exception) { false }
            callback("releveGeminiKeySaved", success.toString())
        }
        @JavascriptInterface fun cancelGemini() {
            geminiRequestId.incrementAndGet()
            geminiActiveConnection?.disconnect()
            callback("releveGeminiCancelled")
        }
        @JavascriptInterface fun analyzeGemini(transcript: String) {
            if (transcript.isBlank() || transcript.length > 50000) {
                callback("releveGeminiError", JSONObject.quote("Transcription vide ou trop longue")); return
            }
            val requestId = geminiRequestId.incrementAndGet()
            Thread {
                var connection: HttpURLConnection? = null
                fun current() = geminiRequestId.get() == requestId
                fun progress(kind: String, model: String, detail: String) {
                    if (current()) callback("releveGeminiProgress", JSONObject.quote(kind),
                        JSONObject.quote(model), JSONObject.quote(detail))
                }
                try {
                    progress("info", "", "Recherche des modèles Gemini disponibles…")
                    val apiKey = loadApiKey()
                    if (apiKey.isEmpty()) throw IllegalStateException("Clé Gemini absente")
                    val prompt = """Tu analyses une transcription de visite immobilière en français. Extrais les pièces dans l'ordre de visite, leurs niveaux et les éventuelles justifications de non-visite et moyens à mettre en œuvre. Un escalier mentionné est une pièce à part entière nommée Escalier : ne l'oublie jamais, même lorsqu'il sert seulement à monter à l'étage. CONSERVE EXACTEMENT les noms particuliers donnés par l'utilisateur, notamment Chambre enfant, Chambre parentale, Chambre d'amis, Salle de jeu, Loveroom, Bureau principal, et tout nom inhabituel. Ne transforme jamais Loveroom en Chambre. Ne numérote que les pièces génériques répétées (Chambre 1, Chambre 2, etc.), jamais les noms particuliers. Ne fusionne jamais plusieurs pièces portant un même nom générique. Préserve leur ordre. Interprète le discours entier, les changements de niveau (monter, descendre, revenir au rez-de-chaussée), et les quantités (trois chambres = trois entrées). Le niveau persiste jusqu'au prochain changement explicite. Ne crée aucune pièce non mentionnée. Si un détail est incertain, laisse le champ vide ou utilise le niveau le plus plausible selon le contexte. Réponds UNIQUEMENT en JSON : {"pieces":[{"name":"Salon","floor":"Rez de chaussée","justification":"","means":""}]}. Niveaux autorisés : Sous-sol, Rez de chaussée, Entresol, 1er étage, 2e étage, 3e étage, 4e étage, 5e étage, Combles, Grenier, Toiture, Extérieur. Les champs justification et means sont vides pour une pièce visitée."""
                    val userMessage = prompt + "\n\nTRANSCRIPTION À ANALYSER :\n" + transcript
                    val payload = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userMessage)))))
                        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
                    // Découverte des modèles autorisés pour cette clé.
                    val availableModels = mutableListOf<String>()
                    var pageToken: String? = null
                    do {
                        val pageUrl = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100" +
                            (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
                        val listing = URL(pageUrl).openConnection() as HttpURLConnection
                        try {
                            listing.setRequestProperty("x-goog-api-key", apiKey)
                            listing.connectTimeout = 8000
                            listing.readTimeout = 12000
                            val listStatus = listing.responseCode
                            if (listStatus !in 200..299) {
                                val raw = listing.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                                val detail = try { JSONObject(raw).getJSONObject("error").optString("message") } catch (_: Exception) { "" }
                                throw IllegalStateException("Liste des modèles HTTP $listStatus : " + detail.take(160))
                            }
                            val listingJson = JSONObject(listing.inputStream.bufferedReader().use { it.readText() })
                            val arr = listingJson.optJSONArray("models") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val model = arr.getJSONObject(i)
                                val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                                if ((0 until methods.length()).any { methods.optString(it) == "generateContent" }) {
                                    val name = model.optString("name").removePrefix("models/")
                                    // Écarter Live, audio, image, TTS et modèles non Gemini.
                                    if (name.startsWith("gemini-") &&
                                        !listOf("live", "audio", "image", "tts", "embedding", "robotics", "transcribe", "speech", "native-audio", "computer-use")
                                            .any { name.contains(it, ignoreCase = true) }) {
                                        availableModels.add(name)
                                    }
                                }
                            }
                            pageToken = listingJson.optString("nextPageToken").ifBlank { null }
                        } finally { listing.disconnect() }
                    } while (pageToken != null && availableModels.size < 500)

                    // Tous les modèles texte proposés par Google, sans limite arbitraire de 4 ou 8.
                    // Classement indicatif par génération puis gamme : Pro, Flash, Flash-Lite.
                    // L'API ne garantit pas qu'un modèle répertorié soit utilisable avec le quota de la clé.
                    fun modelRank(name: String): Long {
                        val version = Regex("""gemini-(\d+)(?:\.(\d+))?""").find(name)
                        val major = version?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val minor = version?.groupValues?.get(2)?.toLongOrNull() ?: 0L
                        val family = when {
                            name.contains("pro", true) -> 3L
                            name.contains("flash-lite", true) -> 1L
                            name.contains("flash", true) -> 2L
                            else -> 0L
                        }
                        val stable = if (name.contains("preview", true) || name.contains("exp", true)) 0L else 1L
                        return major * 100000L + minor * 1000L + family * 10L + stable
                    }
                    val discovered = availableModels.distinct().sortedWith(
                        compareByDescending<String> { modelRank(it) }.thenBy { it }
                    )
                    // Un modèle qui a réellement généré du texte (Gemini 3 Flash)
                    // passe avant les modèles récemment indisponibles, sans les exclure.
                    val workingCandidates = listOf("gemini-3-flash", "gemini-3-flash-preview")
                    if (discovered.isEmpty()) throw IllegalStateException(
                        "Aucun modèle Gemini de génération de texte disponible pour cette clé"
                    )
                    // Le dernier modèle ayant réussi est prioritaire, mais n'empêche jamais
                    // de tester les autres lorsqu'il échoue.
                    val remembered = prefs.getString("last_working_gemini_model", "").orEmpty()
                    val models = (listOf(remembered).filter { it in discovered } +
                        workingCandidates.filter { it in discovered } + discovered).distinct()
                    val failures = mutableListOf<String>()
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    var parsed: JSONObject? = null
                    var chosenModel = ""
                    progress("info", "", "${models.size} modèle(s) compatible(s) trouvé(s)")
                    for ((index, model) in models.withIndex()) {
                        if (!current()) return@Thread
                        progress("trying", model, "Modèle ${index + 1}/${models.size} — analyse en cours")
                        // Évite de laisser l'utilisateur attendre plusieurs minutes.
                        if (android.os.SystemClock.elapsedRealtime() - startedAt > 90000L) {
                            failures.add("délai global de 90 s atteint")
                            break
                        }
                        connection?.disconnect()
                        try {
                            connection = URL(
                                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                            ).openConnection() as HttpURLConnection
                            geminiActiveConnection = connection
                            val conn = requireNotNull(connection)
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                            conn.setRequestProperty("x-goog-api-key", apiKey)
                            conn.connectTimeout = 7000
                            conn.readTimeout = 12000
                            conn.doOutput = true
                            conn.outputStream.use {
                                it.write(payload.toString().toByteArray(Charsets.UTF_8))
                            }
                            val status = conn.responseCode
                            if (status !in 200..299) {
                                val rawError = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                                val detail = try {
                                    JSONObject(rawError).getJSONObject("error").optString("message")
                                } catch (_: Exception) { "" }
                                failures.add("$model: HTTP $status")
                                progress("failed", model, "Échec HTTP $status")
                                // 400 peut être propre à un modèle (format non accepté).
                                // 401/403 indiquent généralement une erreur d'authentification ou d'accès.
                                if (status == 401 || status == 403) {
                                    throw IllegalStateException("Gemini HTTP $status : " + detail.take(160))
                                }
                                if (status == 400) progress("failed", model, detail.take(140))
                                continue
                            }
                            // Un HTTP 200 ne suffit pas : il faut un JSON de pièces exploitable.
                            val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                            val result = response.getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                            val candidate = JSONObject(result)
                            val pieces = candidate.getJSONArray("pieces")
                            if (pieces.length() == 0 || pieces.length() > 200) {
                                failures.add("$model: réponse vide ou invalide")
                                progress("failed", model, "Réponse vide ou invalide")
                                continue
                            }
                            var valid = true
                            for (i in 0 until pieces.length()) {
                                if (pieces.getJSONObject(i).optString("name").isBlank()) valid = false
                            }
                            if (!valid) {
                                failures.add("$model: pièce sans nom")
                                progress("failed", model, "Pièce sans nom")
                                continue
                            }
                            parsed = candidate
                            chosenModel = model
                            progress("success", model, "Analyse réussie")
                            break
                        } catch (_: java.net.SocketTimeoutException) {
                            failures.add("$model: timeout")
                            progress("failed", model, "Délai dépassé")
                        } catch (_: java.net.ConnectException) {
                            failures.add("$model: connexion impossible")
                            progress("failed", model, "Connexion impossible")
                        } catch (e: org.json.JSONException) {
                            failures.add("$model: réponse JSON invalide")
                            progress("failed", model, "Réponse JSON invalide")
                        } finally {
                            connection?.disconnect()
                            geminiActiveConnection = null
                            connection = null
                        }
                    }
                    if (parsed == null) throw IllegalStateException(
                        "Aucun modèle opérationnel : " + failures.joinToString("; ").take(400)
                    )
                    if (!current()) return@Thread
                    prefs.edit().putString("last_working_gemini_model", chosenModel).apply()
                    callback("releveGeminiResult", JSONObject.quote(requireNotNull(parsed).toString()))
                } catch (e: Exception) {
                    if (current()) callback("releveGeminiError", JSONObject.quote(e.message ?: "Analyse IA indisponible"))
                } finally {
                    connection?.disconnect()
                    if (current()) geminiActiveConnection = null
                }
            }.start()
        }
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
