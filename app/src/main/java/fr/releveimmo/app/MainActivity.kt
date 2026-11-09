package fr.releveimmo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.speech.RecognizerIntent
import android.media.MediaRecorder
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : Activity() {
 private lateinit var name: EditText
 private lateinit var description: EditText
 private lateinit var status: TextView
 private lateinit var list: LinearLayout
 private lateinit var plan: PlanView
 private var recorder: MediaRecorder? = null
 private var recordingFile: String? = null
 private var recording = false
 private val pieces = JSONArray()
 private val speechRequest = 10
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = ScrollView(this)
  val body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,30,28,40) }
  root.addView(body)
  fun title(s:String) = TextView(this).apply { text=s; textSize=22f; setTextColor(Color.rgb(22,52,72)); setPadding(0,12,0,14); body.addView(this) }
  fun button(s:String, action:()->Unit) = Button(this).apply { text=s; body.addView(this); setOnClickListener { action() } }
  title("Relevé Immo · Android V1")
  status=TextView(this).apply { text="Relevé pièce par pièce · stockage local"; body.addView(this) }
  name=EditText(this).apply { hint="Nom de la pièce (ex. Salon)"; body.addView(this) }
  description=EditText(this).apply { hint="Décris les ouvertures, orientations, dimensions et accès…"; minLines=4; maxLines=10; gravity=48; body.addView(this) }
  button("🎙 Dicter la description") { dictate() }
  button("⏺ Enregistrer l'audio original / Arrêter") { toggleRecord() }
  button("Analyser et vérifier") { analyze() }
  title("Pièces validées")
  list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; body.addView(this) }
  title("Plan schématique provisoire")
  plan=PlanView().apply { minimumHeight=420; body.addView(this, LinearLayout.LayoutParams(-1,420)) }
  button("Exporter les données JSON") { export() }
  button("Nouvelle visite (effacer)") { android.app.AlertDialog.Builder(this).setMessage("Effacer toutes les pièces enregistrées ?").setNegativeButton("Annuler",null).setPositiveButton("Effacer") { _,_-> piecesClear(); save(); refresh() }.show() }
  setContentView(root)
  load(); refresh()
 }
 private fun permission():Boolean { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) return true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),99); status.text="Autorise le micro puis réessaie."; return false }
 private fun dictate() {
  if(!permission()) return
  val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fr-FR"); putExtra(RecognizerIntent.EXTRA_PROMPT,"Décris cette pièce") }
  try { startActivityForResult(i,speechRequest) } catch(e:Exception) { status.text="Aucun service de reconnaissance vocale disponible : saisis le texte manuellement." }
 }
 @Deprecated("Legacy speech result") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==speechRequest && resultCode==RESULT_OK) { val t=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty(); description.setText((description.text.toString()+" "+t).trim()); status.text="Transcription reçue. Vérifie-la avant analyse." } }
 private fun toggleRecord() {
  if(recording) { try { recorder?.stop(); status.text="Audio enregistré localement : ${recordingFile?.substringAfterLast('/')}" } catch(e:Exception) { status.text="Enregistrement trop court ou interrompu" }; recorder?.release(); recorder=null; recording=false; return }
  if(!permission()) return
  recordingFile=File(filesDir,"audio_${System.currentTimeMillis()}.m4a").absolutePath
  try { @Suppress("DEPRECATION") val r=MediaRecorder(); r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setOutputFile(recordingFile); r.prepare(); r.start(); recorder=r; recording=true; status.text="Enregistrement audio en cours… Appuie à nouveau pour arrêter." } catch(e:Exception) { recorder?.release(); recorder=null; recording=false; status.text="Erreur audio : ${e.message}" }
 }
 private fun analyze() {
  val text=description.text.toString().trim(); val label=name.text.toString().trim()
  if(label.isEmpty() || text.isEmpty()) { status.text="Indique le nom et la description de la pièce."; return }
  val detected=extract(text)
  val details="Pièce : $label\nSurface : ${detected.optString("surface","non précisée")}\nDimensions : ${detected.optString("dimensions","non précisées")}\nÉléments détectés : ${detected.optString("elements","aucun")}\n\nTexte :\n$text\n\nValider ces informations ?"
  android.app.AlertDialog.Builder(this).setTitle("Vérification avant intégration").setMessage(details).setNegativeButton("Corriger",null).setPositiveButton("Valider") { _,_-> val obj=JSONObject().put("nom",label).put("description",text).put("surface",detected.optString("surface")).put("dimensions",detected.optString("dimensions")).put("elements",detected.optString("elements")).put("audio",recordingFile ?: "").put("precision","provisoire"); pieces.put(obj); save(); name.setText(""); description.setText(""); recordingFile=null; refresh() }.show()
 }
 private fun extract(t:String):JSONObject {
  val s=t.lowercase(Locale.FRENCH).replace(',','.')
  val surface=Regex("(\\d+(?:\\.\\d+)?)\\s*(?:m²|m2|mètres? carrés?)").find(s)?.groupValues?.get(1).orEmpty()
  val dims=Regex("(\\d+(?:\\.\\d+)?)\\s*(?:m|mètres?)?\\s*(?:sur|x|×)\\s*(\\d+(?:\\.\\d+)?)").find(s)?.let { "${it.groupValues[1]} × ${it.groupValues[2]} m" }.orEmpty()
  val words=listOf("porte","fenêtre","baie vitrée","nord","sud","est","ouest","couloir","garage","buanderie","chauffe-eau")
  return JSONObject().put("surface",if(surface.isEmpty()) "" else "$surface m²").put("dimensions",dims).put("elements",words.filter { s.contains(it) }.joinToString(", "))
 }
 private fun save() { openFileOutput("visite.json",MODE_PRIVATE).use { it.write(pieces.toString(2).toByteArray()) } }
 private fun load() { try { val a=JSONArray(openFileInput("visite.json").bufferedReader().readText()); for(i in 0 until a.length()) pieces.put(a.getJSONObject(i)) } catch(_:Exception) {} }
 private fun piecesClear() { while(pieces.length()>0) pieces.remove(0) }
 private fun refresh() { list.removeAllViews(); for(i in 0 until pieces.length()) { val p=pieces.getJSONObject(i); val v=TextView(this).apply { text="${i+1}. ${p.optString("nom")} · ${p.optString("surface","surface inconnue")}"; textSize=17f; setPadding(0,10,0,10) }; list.addView(v) }; plan.invalidate(); status.text="${pieces.length()} pièce(s) validée(s) · plan provisoire" }
 private fun export() { val i=Intent(Intent.ACTION_SEND).apply { type="application/json"; putExtra(Intent.EXTRA_TEXT,pieces.toString(2)) }; startActivity(Intent.createChooser(i,"Exporter la visite")) }
 override fun onDestroy() { if(recording) try { recorder?.stop() } catch(_:Exception) {}; recorder?.release(); super.onDestroy() }
 inner class PlanView:View(this) { private val paint=Paint(3)
  override fun onDraw(c:Canvas) { super.onDraw(c); c.drawColor(Color.rgb(247,249,251)); val count=pieces.length(); if(count==0) { paint.color=Color.DKGRAY; paint.textSize=36f; c.drawText("Aucune pièce validée",20f,80f,paint); return }; val cols=2; val w=width/2f; val h=150f; for(i in 0 until count) { val x=(i%cols)*w+10; val y=(i/cols)*h+10; paint.color=Color.rgb(220,234,245); paint.style=Paint.Style.FILL; c.drawRect(x,y,x+w-20,y+h-20,paint); paint.color=Color.rgb(48,75,95); paint.style=Paint.Style.STROKE; paint.strokeWidth=3f; c.drawRect(x,y,x+w-20,y+h-20,paint); paint.style=Paint.Style.FILL; paint.textSize=28f; c.drawText(pieces.getJSONObject(i).optString("nom").take(15),x+12,y+44,paint); paint.textSize=22f; c.drawText(pieces.getJSONObject(i).optString("surface").take(15),x+12,y+76,paint) } }
 }
}
