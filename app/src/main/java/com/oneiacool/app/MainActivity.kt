package com.oneiacool.app

import android.Manifest.permission.*
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.text.Normalizer

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var reconocedor: SpeechRecognizer? = null
    private var permisoPendiente: PermissionRequest? = null
    private var selectorArchivos: ValueCallback<Array<Uri>>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions(arrayOf(RECORD_AUDIO, CAMERA, CALL_PHONE, READ_CONTACTS, ACCESS_FINE_LOCATION), 1)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (!request.isForMainFrame || u.host == "appassets.androidplatform.net" ||
                    u.scheme in listOf("blob", "data", "about")) return false
                abrirExterno(u)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val faltan = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                        checkSelfPermission(CAMERA) != PackageManager.PERMISSION_GRANTED) faltan.add(CAMERA)
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltan.add(RECORD_AUDIO)
                    if (faltan.isEmpty()) request.grant(request.resources)
                    else { permisoPendiente = request; requestPermissions(faltan.toTypedArray(), 3) }
                }
            }
            // Botones de adjuntar (Imagen / Documento / Foto): sin esto no abren nada en el WebView
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                selectorArchivos?.onReceiveValue(null)
                if (p.isCaptureEnabled) {
                    // "Foto": se usa la cámara integrada de la página
                    cb.onReceiveValue(null); selectorArchivos = null
                    js("abrirCamaraAdjunto()")
                    return true
                }
                selectorArchivos = cb
                return try { startActivityForResult(p.createIntent(), 10); true }
                catch (e: Exception) { selectorArchivos = null; cb.onReceiveValue(null); false }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                cb.invoke(origin, true, false)
            }
            // window.open(...) -> se abre fuera de la app (app nativa si existe) sin reemplazar ONEIACOOL
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temporal = WebView(this@MainActivity)
                temporal.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { abrirExterno(r.url); return true }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temporal
                resultMsg.sendToTarget()
                return true
            }
        }
        web.addJavascriptInterface(Puente(), "OneiaNativo")
        // Descargas (PDF, Word, PowerPoint...) generadas en la página como blob:
        web.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("blob:")) js(
                "(async()=>{try{const b=await (await fetch(" + JSONObject.quote(url) + ")).blob();" +
                "const r=new FileReader();r.onload=()=>OneiaNativo.guardarArchivo(r.result.split(',')[1],b.type||'application/octet-stream');" +
                "r.readAsDataURL(b);}catch(e){}})()")
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/oneiacool.html")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            val req = permisoPendiente; permisoPendiente = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) req?.grant(req.resources)
            else req?.deny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10) {
            selectorArchivos?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            selectorArchivos = null
        }
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun abrirExterno(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }

    private fun sinAcentos(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    private fun iniciarVoz() {
        if (checkSelfPermission(RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            js("__oneiaVoz('error','not-allowed')"); return
        }
        reconocedor?.destroy()
        reconocedor = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = js("__oneiaVoz('start','')")
                override fun onPartialResults(p: Bundle?) = enviar("partial", p)
                override fun onResults(p: Bundle?) { enviar("final", p); js("__oneiaVoz('end','')") }
                override fun onError(e: Int) {
                    val tipo = if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "no-speech" else "error"
                    js("__oneiaVoz('error','$tipo')"); js("__oneiaVoz('end','')")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        reconocedor?.startListening(i)
    }

    private fun enviar(tipo: String, b: Bundle?) {
        val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        js("__oneiaVoz('$tipo'," + JSONObject.quote(t) + ")")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }

    override fun onDestroy() { reconocedor?.destroy(); web.destroy(); super.onDestroy() }

    private fun contactoPorNombre(nombre: String): Pair<String, String>? {
        if (checkSelfPermission(READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val n = sinAcentos(nombre).trim()
        if (n.isEmpty()) return null
        var mejor: Pair<String, String>? = null
        var puntos = 0
        val cur = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
        cur?.use {
            while (it.moveToNext()) {
                val nom = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val sn = sinAcentos(nom)
                val p = when {
                    sn == n -> 3
                    sn.startsWith(n) || sn.split(" ").contains(n) -> 2
                    sn.contains(n) -> 1
                    else -> 0
                }
                if (p > puntos) { puntos = p; mejor = Pair(nom, num) }
            }
        }
        return mejor
    }

    // Funciones que ONEIACOOL (la página) puede llamar directamente: window.OneiaNativo.xxx()
    inner class Puente {
        @JavascriptInterface fun abrirApp(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        }
        @JavascriptInterface fun abrirAccion(a: String): Boolean {
            if (!Regex("android\\.(media\\.action|settings)\\.[A-Z_]+").matches(a)) return false
            return try { startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
        }
        @JavascriptInterface fun llamar(num: String): String {
            if (checkSelfPermission(CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(CALL_PHONE), 2) }; return "permiso"
            }
            return try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok"
            } catch (e: Exception) { "error" }
        }
        @JavascriptInterface fun buscarContacto(nombre: String): String {
            val c = contactoPorNombre(nombre) ?: return ""
            return c.second.replace(Regex("[^\\d+]"), "")
        }
        // Devuelve {"nombre":..., "numero":...} con el numero en formato internacional (solo digitos),
        // "" si no existe el contacto o "permiso" si falta el permiso de contactos.
        @JavascriptInterface fun contactoWhatsapp(nombre: String): String {
            if (checkSelfPermission(READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(READ_CONTACTS), 4) }; return "permiso"
            }
            val c = contactoPorNombre(nombre) ?: return ""
            val raw = c.second.replace(Regex("[^\\d+]"), "")
            val tm = getSystemService(TelephonyManager::class.java)
            val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() }
                ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: java.util.Locale.getDefault().country).uppercase()
            val e164 = try { PhoneNumberUtils.formatNumberToE164(raw, iso) } catch (e: Exception) { null }
            var digitos = (e164 ?: raw).replace(Regex("[^\\d]"), "")
            if (e164 == null && raw.startsWith("00")) digitos = raw.substring(2)
            return JSONObject().put("nombre", c.first).put("numero", digitos).toString()
        }
        // Abre el chat de WhatsApp (o WhatsApp Business) con el texto ya escrito. No lo envia: lo toca el usuario.
        @JavascriptInterface fun abrirWhatsapp(num: String, texto: String): String {
            val digitos = num.replace(Regex("[^\\d]"), "")
            if (digitos.isEmpty()) return "error"
            val uri = Uri.parse("https://wa.me/" + digitos + (if (texto.isNotBlank()) "?text=" + Uri.encode(texto) else ""))
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
                try {
                    val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (pkg != null) i.setPackage(pkg)
                    startActivity(i); return "ok"
                } catch (e: Exception) {}
            }
            return "error"
        }
        @JavascriptInterface fun linterna(on: Boolean): Boolean = try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.setTorchMode(id, on); true
        } catch (e: Exception) { false }
        @JavascriptInterface fun escuchar() = runOnUiThread { iniciarVoz() }
        @JavascriptInterface fun detener() = runOnUiThread { reconocedor?.cancel() }
        @JavascriptInterface fun guardarArchivo(b64: String, mime: String) {
            val ext = when {
                mime.contains("wordprocessingml") -> "docx"
                mime.contains("presentationml") -> "pptx"
                mime.contains("spreadsheetml") -> "xlsx"
                else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            }
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "oneiacool_" + System.currentTimeMillis() + "." + ext)
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
            runOnUiThread { Toast.makeText(this@MainActivity, "Guardado en Descargas", Toast.LENGTH_LONG).show() }
        }
    }
}
