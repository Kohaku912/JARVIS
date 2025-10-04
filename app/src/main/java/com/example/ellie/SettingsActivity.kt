package com.example.ellie

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var appStartTime: Long = 0L
    private lateinit var uptimeHandler: android.os.Handler
    private lateinit var uptimeRunnable: Runnable

    // 新しい設定項目のためのEditTextを宣言
    private lateinit var serverAddressInput: EditText
    private lateinit var serverPasswordInput: EditText
    private lateinit var hotwordModelInput: EditText


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)

        // 初期化
        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        appStartTime = prefs.getLong("main_activity_start_time", SystemClock.elapsedRealtime())

        // View取得
        val backButton: Button = findViewById(R.id.backButton)
        val apiKeyInput: EditText = findViewById(R.id.customTextInput)
        val geminiKeyInput: EditText = findViewById(R.id.geminiAPIInput) // Gemini API Key
        val statusIndicator: View = findViewById<View>(R.id.status_indicator)

        serverAddressInput = findViewById(R.id.serverAddressInput)
        serverPasswordInput = findViewById(R.id.serverPasswordInput)
        hotwordModelInput = findViewById(R.id.hotwordModelInput)

        val voiceSwitch: Switch = findViewById(R.id.voiceSwitch)
        val volumeSeekBar: SeekBar = findViewById(R.id.volumeSeekBar)
        val volumeText: TextView = findViewById(R.id.volumeText)

        val voiceAuthSwitch: Switch = findViewById(R.id.voiceAuthSwitch)
        val privateModeSwitch: Switch = findViewById(R.id.privateModeSwitch)

        val versionText: TextView = findViewById(R.id.versionText)
        val uptimeText: TextView = findViewById(R.id.uptimeText)

        // サービスの状態に応じたインジケーターの色設定
        if (ELLIEService.isRunning) statusIndicator.background = getDrawable(R.drawable.status_indicator)
        else statusIndicator.background = getDrawable(R.drawable.status_indicator_red)

        // SharedPreferencesから設定値を読み込み、UIに表示
        apiKeyInput.setText(prefs.getString("porcupine_api_key", getString(R.string.default_porcupine_api_key)))
        geminiKeyInput.setText(prefs.getString("gemini_api_key", getString(R.string.default_gemini_api_key)))
        serverAddressInput.setText(prefs.getString("server_address", getString(R.string.default_server_address)))
        serverPasswordInput.setText(prefs.getString("server_password", getString(R.string.default_server_password)))
        hotwordModelInput.setText(prefs.getString("hotword_model", getString(R.string.default_hotword_model)))

        voiceSwitch.isChecked = prefs.getBoolean("voice_response", resources.getBoolean(R.bool.default_voice_response))
        volumeSeekBar.progress = prefs.getInt("voice_volume", resources.getInteger(R.integer.default_voice_volume))
        volumeText.text = "${volumeSeekBar.progress}%"

        voiceAuthSwitch.isChecked = prefs.getBoolean("voice_auth", resources.getBoolean(R.bool.default_voice_auth))
        privateModeSwitch.isChecked = prefs.getBoolean("private_mode", resources.getBoolean(R.bool.default_private_mode))

        // リスナー設定
        backButton.setOnClickListener { finish() }

        // Gemini API Key Input Listener
        apiKeyInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("porcupine_api_key", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        geminiKeyInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("gemini_api_key", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Server Address Input Listener
        serverAddressInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("server_address", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Server Password Input Listener
        serverPasswordInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("server_password", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Hotword Model Input Listener
        hotwordModelInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("hotword_model", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 各SwitchとSeekBarのリスナー設定（変更なし、デフォルト値の取得方法のみ変更済み）
        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("voice_response", isChecked).apply()
        }

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeText.text = "${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt("voice_volume", seekBar?.progress ?: 0).apply()
            }
        })


        voiceAuthSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("voice_auth", isChecked).apply()
        }

        privateModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("private_mode", isChecked).apply()
        }

        // System Information
        val pkgInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        versionText.text = "v${pkgInfo.versionName}"

        val appElapsed = SystemClock.elapsedRealtime() - appStartTime
        uptimeText.text = formatMillisToHMS(appElapsed)
        uptimeHandler = android.os.Handler(mainLooper)
        uptimeRunnable = object : Runnable {
            override fun run() {
                val appElapsed = SystemClock.elapsedRealtime() - appStartTime
                uptimeText.text = formatMillisToHMS(appElapsed)
                uptimeHandler.postDelayed(this, 1000)
            }
        }
        uptimeHandler.post(uptimeRunnable)

    }
    override fun onDestroy() {
        super.onDestroy()
        uptimeHandler.removeCallbacks(uptimeRunnable)
    }
    private fun formatMillisToHMS(millis: Long): String {
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}