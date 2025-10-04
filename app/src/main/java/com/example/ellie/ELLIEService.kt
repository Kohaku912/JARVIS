package com.example.ellie

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class ELLIEService : Service(), TextToSpeech.OnInitListener {

    private lateinit var prefs: SharedPreferences

    companion object {
        var isRunning = false
    }
    private val NOTIF_CHANNEL_ID = "ellie_channel"
    private val NOTIF_ID = 1

    private var HOTWORD_MODEL: String = ""
    private var SERVER_ADDRESS: String = ""
    private var SERVER_PASSWORD: String = ""
    private var ACCESS_KEY: String = ""
    private var GEMINI_KEY: String = ""
    private var PC_ADDRESS: String = "192.168.1.1:5000"
    private var PC_MAC_ADDRESS: String = "FF:FF:FF:FF:FF:FF"

    private var porcupineManager: PorcupineManager? = null
    private var windowManager: WindowManager? = null
    private val httpClient = OkHttpClient()
    private var overlayView: View? = null
    private var tvStatus: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private val packageNames = mutableSetOf<String>()
    private var textToSpeech: TextToSpeech? = null
    private var orbitView: OrbitView? = null
    private var overlayHideRunnable: Runnable? = null
    private val binder = LocalBinder()
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())

    private var CLASSIFY_TEXT: String = """
        You are a command classifier.
        Categorize the user message into one category and extract parameters.
        Output must be JSON with keys: "category" and (if any) "parameters".
        Categories:
        - power_pc: {on: Boolean}
        - launch_app: {app_name: String}
        - room_light: {on: Boolean}
        - flashlight: {on: Boolean}
        - sendmessage: {platform: Int, to: String, message: String, language: Int}
        - play_music: {`in`: Int, title: String}
        - question: {text: String}
        IDs:
        - in: PC=0, SmartPhone=1
        - platform: Line=0, Discord=1
        - language: ja=0, en=1
        - app_names: ${packageNames.joinToString(",")}
        Examples:
        User: "turn on the PC"
        → {"category": "power_pc", "parameters": {"on": true}}
        User: "what is the weather today?"
        → {"category": "question", "parameters": {"text": "what is the weather today?"}}
        User: "launch the settings app"
        → {"category": "launch_app", "parameters": {"app_name": "com.android.settings"}}
        """
    private val QUESTION_TEXT: String = """
        You are a question answering AI like J.A.R.V.I.S. Your task is to answer user questions based on the provided context.
        The output length should be concise, ideally within 50 words.
        Examples:
        - User: "how long rabbit life"
          Output: sir. While wild rabbits typically possess a lifespan of merely one to two years, domesticated rabbits, with adequate care, are capable of surviving for a decade or even longer.
        """

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)

        loadSettings()

        setupNotification()
        initializeComponents()
        setupOverlay()
        setupSpeechRecognizer()
        retrieveInstalledPackages()
    }
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        textToSpeech?.shutdown()
        porcupineManager?.stop()
        overlayView?.let { windowManager?.removeView(it) }
        speechRecognizer?.destroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun loadSettings() {
        HOTWORD_MODEL = prefs.getString("hotword_model", getString(R.string.default_hotword_model)) ?: getString(R.string.default_hotword_model)
        SERVER_ADDRESS = prefs.getString("server_address", getString(R.string.default_server_address)) ?: getString(R.string.default_server_address)
        SERVER_PASSWORD = prefs.getString("server_password", getString(R.string.default_server_password)) ?: getString(R.string.default_server_password)
        ACCESS_KEY = prefs.getString("porcupine_api_key", getString(R.string.default_porcupine_api_key)) ?: getString(R.string.default_porcupine_api_key)
        GEMINI_KEY = prefs.getString("gemini_api_key", getString(R.string.default_gemini_api_key)) ?: getString(R.string.default_gemini_api_key)
    }

    private fun setupNotification() {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.app_name) + " initializing…"))
    }

    private fun initializeComponents() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager!!.cameraIdList[0]

        textToSpeech = TextToSpeech(this, this)
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { overlayView?.visibility = View.GONE }
            }
            override fun onError(utteranceId: String?) {
                handler.post { overlayView?.visibility = View.GONE }
            }
        })

        startPorcupine()
        orbitView = OrbitView(this)
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay, null).also { view ->
            tvStatus = view.findViewById(R.id.tvStatus)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            windowManager?.addView(view, params)
            view.visibility = View.GONE
        }
    }

    private fun setupSpeechRecognizer() {
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        createSpeechRecognizer()
    }

    private fun createSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                return
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        overlayHideRunnable?.let { handler.removeCallbacks(it) }
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        isListening = false
                        hideOverlay()
                        startPorcupine()
                    }

                    override fun onResults(results: Bundle?) {
                        val transcript = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        isListening = false
                        if (transcript != null) {
                            handler.post {
                                tvStatus?.text = transcript
                                overlayView?.visibility = View.VISIBLE
                            }
                            when{
                                transcript.startsWith("flashlight") -> {
                                    when(transcript){
                                        "flashlight on" -> setLight(true)
                                        "flashlight off" -> setLight(false)
                                        else -> processTranscript(transcript)
                                    }
                                }
                                transcript.startsWith("open") || transcript.startsWith("launch") -> {
                                    when{
                                        transcript.contains("setting") -> launchApp("com.android.settings")
                                        transcript.contains("chrome") -> launchApp("com.android.chrome")
                                        transcript.contains("youtube") -> launchApp("com.google.android.youtube")
                                        transcript.contains("gmail") -> launchApp("com.google.android.gm")
                                        else -> processTranscript(transcript)
                                    }
                                }
                                else -> processTranscript(transcript)
                            }
                        }
                        startPorcupine()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (_: Exception) {
        }
    }

    private fun startListening() {
        if (isListening) {
            return
        }
        if (!hasRecordAudioPermission()) {
            say("Microphone permission required. Please enable in app settings.")
            hideOverlay()
            return
        }
        if (speechRecognizer == null) {
            createSpeechRecognizer()
        }
        if (speechIntent == null) {
            return
        }
        try {
            speechRecognizer!!.startListening(speechIntent)
            isListening = true
        } catch (_: Exception) {
            isListening = false
        }
    }

    private fun startPorcupine() {
        if (HOTWORD_MODEL.isEmpty()) {
            HOTWORD_MODEL = getString(R.string.default_hotword_model)
        }
        if (ACCESS_KEY.isEmpty()) {
            ACCESS_KEY = getString(R.string.default_porcupine_api_key) // Fallback to default
        }

        porcupineManager = PorcupineManager.Builder()
            .setAccessKey(ACCESS_KEY)
            .setKeywordPath(File(filesDir, HOTWORD_MODEL).absolutePath)
            .setSensitivity(0.5f)
            .build(this) { showOverlay() }
        porcupineManager?.start()
    }

    private fun showOverlay() {
        handler.post {
            porcupineManager?.stop()
            porcupineManager = null
            overlayView?.visibility = View.VISIBLE
            tvStatus?.text = getString(R.string.app_name)

            if (!hasRecordAudioPermission()) {
                say("Microphone permission required. Please enable in app settings.")
                return@post
            }
            startListening()

            overlayHideRunnable?.let { handler.removeCallbacks(it) }
            overlayHideRunnable = Runnable {
                speechRecognizer?.stopListening()
                startPorcupine()
                hideOverlay()
            }.also {
                handler.postDelayed(it, 3_000)
            }

            val notif = buildNotification("Listening…")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif)
        }
    }

    private fun hideOverlay() {
        handler.post {
            overlayView?.visibility = View.GONE
            overlayHideRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let { tts ->
                val locale = Locale.US
                if (tts.isLanguageAvailable(locale) > TextToSpeech.LANG_AVAILABLE) {
                    tts.language = Locale.US
                }
                val volume = prefs.getInt("voice_volume", resources.getInteger(R.integer.default_voice_volume)) / 100f
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                textToSpeech?.setSpeechRate(1.0f)
                textToSpeech?.setPitch(1.0f)
            }
        }
    }

    private fun say(message: String) {
        handler.post {
            tvStatus?.text = message
            overlayView?.visibility = View.VISIBLE
        }
        val voiceResponseEnabled = prefs.getBoolean("voice_response", resources.getBoolean(R.bool.default_voice_response))
        if (voiceResponseEnabled) {
            val volume = prefs.getInt("voice_volume", resources.getInteger(R.integer.default_voice_volume)) / 100f
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "utteranceId")
        } else {
            handler.postDelayed({ hideOverlay() }, 3000)
        }
    }
    private fun classifyLocally(text: String): Pair<String, JSONObject>? {
        val t = text.lowercase(Locale.US)
        val params = JSONObject()
        when {
            t.startsWith("flashlight") || t.startsWith("turn on flashlight") || t.startsWith("flashlight on") -> {
                params.put("on", true)
                return Pair("flashlight", params)
            }
            t.startsWith("flashlight off") || t.contains("turn off flashlight") -> {
                params.put("on", false)
                return Pair("flashlight", params)
            }
            t.startsWith("open ") || t.startsWith("launch ") -> {
                // 簡単なパッケージ名マップ
                val pkg = when {
                    t.contains("setting") -> "com.android.settings"
                    t.contains("chrome") -> "com.android.chrome"
                    t.contains("youtube") -> "com.google.android.youtube"
                    t.contains("gmail") -> "com.google.android.gm"
                    else -> null
                }
                if (pkg != null) {
                    params.put("app_name", pkg)
                    return Pair("launch_app", params)
                }
            }
            t.contains("turn on pc") || t.contains("power on pc") || t.contains("turn on the pc") -> {
                params.put("on", true)
                return Pair("power_pc", params)
            }
            t.contains("turn off pc") || t.contains("shutdown pc") || t.contains("power off pc") -> {
                params.put("on", false)
                return Pair("power_pc", params)
            }
            t.startsWith("play ") && t.contains("on phone").not() -> {
                // play music をローカルで処理 -> youtube 検索を開く
                params.put("in", 1) // phone
                params.put("title", text.substringAfter("play").trim())
                return Pair("play_music", params)
            }
        }
        return null
    }

    fun processTranscript(transcript: String) {
        Thread {
            try {
                // 1) まずローカル簡易判定
                val local = classifyLocally(transcript)
                if (local != null) {
                    handleLocalCommand(local.first, local.second)
                    return@Thread
                }
                val classifyPrompt = """
                $CLASSIFY_TEXT
                $transcript
            """.trimIndent()
                val geminiText = callGeminiForClassification(classifyPrompt)
                val jsonString = extractJsonBlock(geminiText)
                if (jsonString != null) {
                    val obj = JSONObject(jsonString)
                    val category = obj.getString("category")
                    val params = obj.optJSONObject("parameters") ?: JSONObject()
                    handleLocalCommand(category, params)
                } else {
                    val qaPrompt = "$QUESTION_TEXT\n$transcript"
                    val answer = callGeminiForClassification(qaPrompt) ?: "No response from AI"
                    say(answer)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                say("Internal error processing your request")
            }
        }.start()
    }

    private fun callGeminiForClassification(prompt: String): String? {
        try {
            System.setProperty("GEMINI_API_KEY", GEMINI_KEY)

            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = GEMINI_KEY
            )

            var response = ""
            runBlocking {
                withContext(Dispatchers.IO) {
                    response = model.generateContent(prompt).text.toString()
                }
            }
            return response
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun questGemini(prompt: String): String{
        try {
            System.setProperty("GEMINI_API_KEY", GEMINI_KEY)

            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = GEMINI_KEY
            )

            var response = ""
            runBlocking {
                withContext(Dispatchers.IO) {
                    response = model.generateContent(prompt).text.toString()
                }
            }
            return response
        } catch (e: Exception) {
            e.printStackTrace()
            return "Gemini ERROR"
        }
    }

    private fun extractJsonBlock(text: String?): String? {
        if (text == null) return null
        val regex = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text)
        return match?.groupValues?.get(1)
    }
    private fun handleLocalCommand(category: String, params: JSONObject) {
        when (category) {
            "report" -> {
                val msg = params.optString("message", "Report")
                say(msg)
            }
            "flashLight" , "flashlight" -> {
                val on = params.optBoolean("on", false)
                setLight(on)
            }
            "launch_app" -> {
                val name = params.optString("app_name", "")
                if (name.isNotEmpty()) launchApp(name)
            }
            "power_pc" -> {
                val on = params.optBoolean("on", false)
                if (on) {
                    // WOL
                    val mac = PC_MAC_ADDRESS // 既存定義を利用
                    sendWakeOnLan(mac, PC_ADDRESS) // broadcast/ip の扱いは下で
                    say("Sent wake on lan")
                } else {
                    // shutdown via http to PC
                    Thread {
                        try {
                            val url = URL("http://$PC_ADDRESS/shutdown")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            val code = conn.responseCode
                            conn.disconnect()
                            say("Shutdown command sent")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            say("Failed to send shutdown")
                        }
                    }.start()
                }
            }
            "getClipBoard" -> {
                val text = getClipboard()
                // 直接ユーザーに読ませる
                say("Clipboard: $text")
            }
            "play_music" -> {
                val inTarget = params.optInt("in", 1)
                val title = params.optString("title", "")
                if (inTarget == 1) {
                    // phone -> open youtube search
                    openYouTubeSearch(title)
                } else {
                    say("Playing $title on PC (not implemented locally).")
                }
            }
            "question" -> {
                var answer = questGemini("""
                $QUESTION_TEXT
                ${params.optString("text", "ERROR")}
            """.trimIndent())
                say(answer)
            }
            else -> {
                say("Unknown command $category")
            }
        }
    }

    private fun openYouTubeSearch(query: String) {
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            putExtra("query", query)
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // fallback: web search url
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "utf-8")))
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(web)
        }
    }

    /** Wake-on-LAN: MAC -> magic packet を送信 */
    private fun sendWakeOnLan(macStr: String, ip: String) {
        try {
            val macBytes = ByteArray(6)
            val hex = macStr.replace(":", "").replace("-", "")
            for (i in 0 until 6) {
                macBytes[i] = Integer.parseInt(hex.substring(i*2, i*2+2), 16).toByte()
            }
            val bytes = ByteArray(6 + 16 * macBytes.size)
            for (i in 0 until 6) bytes[i] = 0xFF.toByte()
            var pos = 6
            for (i in 0 until 16) {
                System.arraycopy(macBytes, 0, bytes, pos, macBytes.size)
                pos += macBytes.size
            }
            // 送信先はブロードキャスト (255.255.255.255) でも、ルータのブロードキャストアドレスにする場合あり
            val address = InetAddress.getByName("255.255.255.255")
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val packet = DatagramPacket(bytes, bytes.size, address, 9)
                socket.send(packet)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun playMusic(title: String, imageURL: String, audioBase64: String) {
        say("Playing $title ...")

        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)

        val audioFile = File.createTempFile("audio_", ".mp3", cacheDir)
        audioFile.writeBytes(audioBytes)
        val intent = Intent(this, MusicPlayerService::class.java).apply {
            action = MusicPlayerService.ACTION_PLAY_AUDIO
            putExtra(MusicPlayerService.EXTRA_TITLE, title)
            putExtra(MusicPlayerService.EXTRA_AUDIO_PATH, audioFile.name)
            putExtra(MusicPlayerService.EXTRA_IMAGE_URL, imageURL)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun getClipboard(): String {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
            val item = clipboard.primaryClip!!.getItemAt(0)
            return item.text.toString()
        } else {
            return "NO DATA"
        }
        say("Sent the clipboard")
        handler.post {
            hideOverlay()
        }

    }

    private fun retrieveInstalledPackages() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(mainIntent, 0)
        }
        for (info in resolveInfos) {
            packageNames.add(info.activityInfo.packageName)
        }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            say("Launched ${packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName,0))}")
            handler.post {
                hideOverlay()
            }
        }
    }

    private fun setLight(on: Boolean) {
        if (cameraManager != null && !cameraId.isNullOrEmpty()) {
            cameraManager!!.setTorchMode(cameraId!!, on)
            if (on) say("Turned on the flashlight,sir")
            else say("Turned off the flashlight,sir")
        } else {
            say("Flashlight not available or camera ID not found.")
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_atomic)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.app_name) + " status",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(chan)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ELLIEService = this@ELLIEService
    }
}