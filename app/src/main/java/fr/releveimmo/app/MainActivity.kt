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
    private val openXmlCode = 45
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
        web.loadUrl("file:///android_asset/etape1/index.html")
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
    // Clés supplémentaires : chaque emplacement est chiffré avec Android Keystore.
    // Aucun fournisseur externe n'est appelé tant que son adaptateur API n'est pas intégré.
    private fun providerSlot(provider: String, slot: Int): String? {
        if (provider !in setOf("groq", "openrouter") || slot !in 1..3) return null
        return "provider_${provider}_$slot"
    }
    private fun storeProviderKey(provider: String, slot: Int, value: String): Boolean {
        val name = providerSlot(provider, slot) ?: return false
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return true }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
        return true
    }
    private fun loadProviderKey(provider: String): String {
        val name = providerSlot(provider, 1) ?: return ""
        val encoded = prefs.getString(name, null) ?: return ""
        val data = Base64.decode(encoded, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cipherKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
    private fun hasProviderKey(provider: String, slot: Int): Boolean {
        val name = providerSlot(provider, slot) ?: return false
        val encoded = prefs.getString(name, null) ?: return false
        val data = Base64.decode(encoded, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cipherKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).isNotEmpty()
    }
    private fun callback(name: String, vararg args: String) {
        runOnUiThread { web.evaluateJavascript("window.$name && window.$name(${args.joinToString(",")});", null) }
    }
    private fun analyzeExternal(transcript: String, requestId: Int, previousError: String) {
        if (geminiRequestId.get() != requestId) return
        val prompt = """Tu analyses une transcription de visite immobilière en français. Extrais les pièces dans l'ordre de visite, leurs niveaux et les éventuelles justifications de non-visite et moyens à mettre en œuvre. Un escalier mentionné est une pièce à part entière nommée Escalier : ne l'oublie jamais, même lorsqu'il sert seulement à monter à l'étage. CONSERVE EXACTEMENT les noms particuliers donnés par l'utilisateur, notamment Chambre enfant, Chambre parentale, Chambre d'amis, Salle de jeu, Loveroom, Bureau principal, et tout nom inhabituel. Ne transforme jamais Loveroom en Chambre. Ne numérote que les pièces génériques répétées (Chambre 1, Chambre 2, etc.), jamais les noms particuliers. Ne fusionne jamais plusieurs pièces portant un même nom générique. Préserve leur ordre. Interprète le discours entier, les changements de niveau (monter, descendre, revenir au rez-de-chaussée), et les quantités (trois chambres = trois entrées). Le niveau persiste jusqu'au prochain changement explicite. Ne crée aucune pièce non mentionnée. Si un détail est incertain, laisse le champ vide ou utilise le niveau le plus plausible selon le contexte. Réponds UNIQUEMENT en JSON : {"pieces":[{"name":"Salon","floor":"Rez de chaussée","justification":"","means":""}]}. Niveaux autorisés : Sous-sol, Rez de chaussée, Entresol, 1er étage, 2e étage, 3e étage, 4e étage, 5e étage, Combles, Grenier, Toiture, Extérieur. Les champs justification et means sont vides pour une pièce visitée."""
        val message = prompt + "\n\nTRANSCRIPTION À ANALYSER :\n" + transcript
        val failures = mutableListOf(previousError.take(180))
        val started = android.os.SystemClock.elapsedRealtime()
        for (provider in listOf("groq", "openrouter")) {
            if (geminiRequestId.get() != requestId) return
            val key = try { loadProviderKey(provider) } catch (_: Exception) { "" }
            if (key.isBlank()) continue
            val base = if (provider == "groq") "https://api.groq.com/openai/v1" else "https://openrouter.ai/api/v1"
            fun fetchModels(): List<String> {
                val c = URL("$base/models").openConnection() as HttpURLConnection
                try {
                    c.setRequestProperty("Authorization", "Bearer $key")
                    c.connectTimeout = 7000; c.readTimeout = 10000
                    if (c.responseCode !in 200..299) throw IllegalStateException("Liste modèles HTTP ${c.responseCode}")
                    val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: JSONArray()
                    val found = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id")
                        if (id.isBlank()) continue
                        if (provider == "openrouter") {
                            if (!id.endsWith(":free")) continue
                        } else {
                            if (!id.contains("llama",true) && !id.contains("qwen",true) && !id.contains("gpt-oss",true) && !id.contains("gemma",true)) continue
                            if (listOf("guard","safeguard","vision","whisper","audio","tts","transcribe","moderation").any { id.contains(it,true) }) continue
                        }
                        found.add(id)
                    }
                    return found.sortedWith(compareByDescending<String> { it.contains("70b") || it.contains("120b") || it.contains("large") }.thenBy { it }).take(12)
                } finally { c.disconnect() }
            }
            val models = try { fetchModels() } catch (e: Exception) {
                failures.add("$provider : ${e.message}"); continue
            }
            for (model in models) {
                if (geminiRequestId.get() != requestId) return
                if (android.os.SystemClock.elapsedRealtime() - started > 90000L) break
                callback("releveGeminiProgress", JSONObject.quote("trying"), JSONObject.quote("$provider / $model"), JSONObject.quote("Analyse en cours"))
                var c: HttpURLConnection? = null
                try {
                    val payload = JSONObject().put("model",model).put("temperature",0.1)
                        .put("messages",JSONArray().put(JSONObject().put("role","user").put("content",message)))
                    if (provider == "groq") payload.put("response_format",JSONObject().put("type","json_object"))
                    c = URL("$base/chat/completions").openConnection() as HttpURLConnection
                    geminiActiveConnection = c
                    c.requestMethod="POST";c.doOutput=true
                    c.setRequestProperty("Authorization","Bearer $key")
                    c.setRequestProperty("Content-Type","application/json; charset=UTF-8")
                    c.connectTimeout=7000;c.readTimeout=12000
                    c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    if (c.responseCode !in 200..299) {
                        failures.add("$provider / $model : HTTP ${c.responseCode}");continue
                    }
                    val response=JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    var answer=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                    if (answer.startsWith("```")) answer=answer.replace(Regex("^```(?:json)?\\s*|\\s*```$"), "").trim()
                    val obj=JSONObject(answer)
                    val pieces=obj.optJSONArray("pieces") ?: continue
                    if (pieces.length() !in 1..200 || (0 until pieces.length()).any { pieces.optJSONObject(it)?.optString("name").isNullOrBlank() }) continue
                    if (geminiRequestId.get() != requestId) return
                    callback("releveGeminiProgress",JSONObject.quote("success"),JSONObject.quote("$provider / $model"),JSONObject.quote("Analyse réussie"))
                    callback("releveGeminiResult",JSONObject.quote(obj.toString()))
                    return
                } catch (e: Exception) {
                    failures.add("$provider / $model : ${e.message?.take(65)}")
                } finally { c?.disconnect();geminiActiveConnection=null }
            }
        }
        if (geminiRequestId.get() == requestId) callback("releveGeminiError",JSONObject.quote(failures.joinToString(" ; ").take(350)))
    }

    // Étape 2 : IA commune à toutes les étapes, avec clés conservées dans Android Keystore.
    private fun analyzeStep2Native(transcript: String, roomsJson: String) {
        if (transcript.isBlank() || transcript.length > 50000) {
            callback("releveStep2Error", JSONObject.quote("Dictée vide ou trop longue"))
            return
        }
        val requestId = geminiRequestId.incrementAndGet()
        Thread {
            val errors = mutableListOf<String>()
            fun current() = geminiRequestId.get() == requestId
            fun progress(message: String) { if (current()) callback("releveStep2Progress", JSONObject.quote(message)) }
            val roomArray = try { JSONArray(roomsJson) } catch (_: Exception) { JSONArray() }
            val prompt = """Tu es un diagnostiqueur immobilier et tu convertis une dictée française imparfaitement transcrite en composants Liciel.
Chaque élément physique est UNE ligne distincte, même si plusieurs partagent les mêmes matériaux.
Exemple : « murs A B C D en plâtre peinture » = 4 entrées Mur - A, Mur - B, Mur - C, Mur - D.
« l'embrasure de la fenêtre I en plâtre peinture, l'allège de la fenêtre I en plâtre peinture, la fenêtre un en PVC » = 3 entrées DISTINCTES :
Embrasure fenêtre - I / Substrat : Plâtre - Revêtement : Peinture ;
Allège fenêtre - I / Substrat : Plâtre - Revêtement : Peinture ;
Fenêtre 1 - I / Substrat : PVC.
Distingue fenêtres, volets, embrasures, allèges, portes, grilles, murs, sols, plafonds, plinthes, poutres.
Corrige les erreurs évidentes de reconnaissance vocale (plaintes -> plinthes, plate -> plâtre, alege -> allège) sans inventer.
Associe chaque composant à la pièce réellement mentionnée parmi la liste fournie. Conserve le niveau de la pièce.
Pour chaque ligne, Type doit être un libellé comme « Mur - A », « Fenêtre 1 - I », « Plafond » ; Informations contient « Substrat : ... - Revêtement : ... » selon les éléments réellement dictés.
Respecte les indications d'époque >1949, après 1949, 1940 telles qu'elles sont dictées, sans transformer 1940 en 1949.
Si un composant est ambigu, ajoute un avertissement et ne lui invente pas de matériaux.
N'invente JAMAIS de mesures plomb ni de dégradations CREP. Ne fusionne pas les composants.
Réponds UNIQUEMENT avec un objet JSON : {"components":[{"room":"Salon cuisine","type":"Mur - A","info":"Substrat : Plâtre - Revêtement : Peinture"}],"warnings":[]}.
La propriété room doit correspondre au nom d'une pièce fournie. Ne renvoie aucun XML.
PIÈCES DE L'ÉTAPE 1 : """ + roomArray.toString() + "\nDICTÉE :\n" + transcript
            fun valid(raw: String): JSONObject? {
                val cleaned = raw.trim().replace(Regex("^```(?:json)?\\s*|\\s*```$", RegexOption.IGNORE_CASE), "").trim()
                val obj = try { JSONObject(cleaned) } catch (_: Exception) { return null }
                val components = obj.optJSONArray("components") ?: return null
                if (components.length() !in 1..500) return null
                for (i in 0 until components.length()) {
                    val row = components.optJSONObject(i) ?: return null
                    if (row.optString("room").isBlank() || row.optString("type").isBlank()) return null
                    // Ne pas accepter des pièces inventées : l'étape 2 dépend de l'étape 1.
                    if (!(0 until roomArray.length()).any { j ->
                        roomArray.optJSONObject(j)?.optString("name")?.trim()
                            ?.equals(row.optString("room").trim(), ignoreCase = true) == true
                    }) return null
                }
                return obj
            }
            fun post(url: String, key: String, payload: JSONObject, google: Boolean): String {
                val c = URL(url).openConnection() as HttpURLConnection
                geminiActiveConnection = c
                try {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.connectTimeout = 7000; c.readTimeout = 18000
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    if (google) c.setRequestProperty("x-goog-api-key", key)
                    else c.setRequestProperty("Authorization", "Bearer $key")
                    c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val status = c.responseCode
                    if (status !in 200..299) throw IllegalStateException("HTTP $status")
                    val result = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    return if (google) {
                        val parts = result.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                        (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
                    } else result.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                } finally { c.disconnect(); if (geminiActiveConnection === c) geminiActiveConnection = null }
            }
            try {
                val geminiKey = try { loadApiKey() } catch (_: Exception) { "" }
                if (geminiKey.isNotBlank()) {
                    progress("Gemini : recherche des modèles disponibles…")
                    val models = try {
                        val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100").openConnection() as HttpURLConnection
                        try {
                            c.setRequestProperty("x-goog-api-key", geminiKey)
                            c.connectTimeout = 7000; c.readTimeout = 10000
                            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                            val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: JSONArray()
                            val names = mutableListOf<String>()
                            for (i in 0 until arr.length()) {
                                val m = arr.getJSONObject(i)
                                val name = m.optString("name").removePrefix("models/")
                                val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                                if (name.startsWith("gemini-") &&
                                    (0 until methods.length()).any { methods.optString(it) == "generateContent" } &&
                                    !listOf("audio","image","tts","live","embedding","speech","robotics").any { name.contains(it,true) })
                                    names.add(name)
                            }
                            (listOf(prefs.getString("last_working_gemini_model","").orEmpty()) +
                                names.sortedWith(compareByDescending<String> { it.contains("flash",true) }.thenByDescending { it }))
                                .filter { it in names }.distinct().take(10)
                        } finally { c.disconnect() }
                    } catch (e: Exception) { errors.add("Gemini : ${e.message}"); emptyList() }
                    for (model in models) {
                        if (!current()) return@Thread
                        progress("Gemini / $model : analyse…")
                        try {
                            val payload = JSONObject().put("contents",JSONArray().put(JSONObject()
                                .put("role","user").put("parts",JSONArray().put(JSONObject().put("text",prompt)))))
                                .put("generationConfig",JSONObject().put("responseMimeType","application/json").put("temperature",0.1))
                            val result = valid(post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",geminiKey,payload,true))
                            if (result != null) {
                                if (!current()) return@Thread
                                prefs.edit().putString("last_working_gemini_model",model).apply()
                                callback("releveStep2Result",JSONObject.quote(result.toString()),JSONObject.quote("Gemini / $model"))
                                return@Thread
                            }
                            errors.add("Gemini / $model : résultat invalide")
                        } catch (e: Exception) { errors.add("Gemini / $model : ${e.message}") }
                    }
                }
                for (provider in listOf("groq","openrouter")) {
                    if (!current()) return@Thread
                    val key = try { loadProviderKey(provider) } catch (_: Exception) { "" }
                    if (key.isBlank()) continue
                    val base = if (provider=="groq") "https://api.groq.com/openai/v1" else "https://openrouter.ai/api/v1"
                    progress("$provider : recherche des modèles…")
                    val models = try {
                        val c = URL("$base/models").openConnection() as HttpURLConnection
                        try {
                            c.setRequestProperty("Authorization","Bearer $key")
                            c.connectTimeout=7000;c.readTimeout=10000
                            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                            val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data") ?: JSONArray()
                            val found=mutableListOf<String>()
                            for(i in 0 until arr.length()) {
                                val id=arr.optJSONObject(i)?.optString("id").orEmpty()
                                if(provider=="openrouter" && id.endsWith(":free")) found.add(id)
                                if(provider=="groq" &&
                                    listOf("llama","qwen","gpt-oss","gemma").any { id.contains(it,true) } &&
                                    listOf("guard","audio","whisper","tts","vision","moderation").none { id.contains(it,true) }) found.add(id)
                            }
                            found.sortedWith(compareByDescending<String> { it.contains("70b")||it.contains("120b")||it.contains("large") }.thenBy { it }).take(12)
                        } finally { c.disconnect() }
                    } catch (e: Exception) { errors.add("$provider : ${e.message}"); emptyList() }
                    for(model in models) {
                        if(!current()) return@Thread
                        progress("$provider / $model : analyse…")
                        try {
                            val payload=JSONObject().put("model",model).put("temperature",0.1)
                                .put("messages",JSONArray().put(JSONObject().put("role","user").put("content",prompt)))
                            if(provider=="groq") payload.put("response_format",JSONObject().put("type","json_object"))
                            val result=valid(post("$base/chat/completions",key,payload,false))
                            if(result!=null) {
                                if(!current()) return@Thread
                                callback("releveStep2Result",JSONObject.quote(result.toString()),JSONObject.quote("$provider / $model"))
                                return@Thread
                            }
                            errors.add("$provider / $model : résultat invalide")
                        } catch(e:Exception) { errors.add("$provider / $model : ${e.message}") }
                    }
                }
                if(current()) callback("releveStep2Error",JSONObject.quote(
                    "Aucune IA n'a fourni de résultat valide. "+errors.takeLast(5).joinToString(" ; ").take(300)))
            } catch(e:Exception) {
                if(current()) callback("releveStep2Error",JSONObject.quote(e.message?:"Analyse IA indisponible"))
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface fun analyzeStep2(transcript: String, roomsJson: String) = analyzeStep2Native(transcript, roomsJson)
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
        @JavascriptInterface fun setProviderKey(provider: String, slot: Int, key: String) {
            val success = try { storeProviderKey(provider, slot, key.trim()) } catch (_: Exception) { false }
            callback("releveProviderKeySaved", JSONObject.quote(provider), slot.toString(), success.toString(),
                (success && key.isNotBlank()).toString())
        }
        @JavascriptInterface fun getProviderKeyStatus() {
            val result = JSONObject()
            for (provider in listOf("groq", "openrouter")) {
                val values = JSONArray()
                for (slot in 1..3) values.put(try { hasProviderKey(provider, slot) } catch (_: Exception) { false })
                result.put(provider, values)
            }
            callback("releveProviderKeyStatus", JSONObject.quote(result.toString()))
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
                    if (current()) analyzeExternal(transcript, requestId, e.message ?: "Gemini indisponible")
                } finally {
                    connection?.disconnect()
                    if (current()) geminiActiveConnection = null
                }
            }.start()
        }
        @JavascriptInterface fun openXml() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/xml", "text/xml", "text/plain", "application/octet-stream"))
                }
                try { startActivityForResult(intent, openXmlCode) }
                catch (_: Exception) { sendFileStatus("Import XML indisponible") }
            }
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
        if (requestCode == openXmlCode) {
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    val bytes = contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Lecture impossible")
                    if (bytes.size > 5_000_000) throw IllegalStateException("XML trop volumineux")
                    callback("releveNativeXmlImported", JSONObject.quote(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                } catch (_: Exception) { sendFileStatus("Échec de l'import XML") }
            }
            return
        }
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
