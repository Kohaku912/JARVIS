package com.example.ellie

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQ_OVERLAY = 1001
    }
    private var isServiceRunning = false
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var sendText: EditText
    private lateinit var sendButton: Button
    private lateinit var settingsButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            ensureOverlayPermission()
        } else {
            statusText.text = "Permissions required to run " + getString(R.string.app_name)
        }
    }
    private var serviceBound = false
    private var myService: ELLIEService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ELLIEService.LocalBinder
            myService = localBinder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        sendText = findViewById<EditText>(R.id.sendText)
        sendButton = findViewById<Button>(R.id.sendButton)
        settingsButton = findViewById<Button>(R.id.settingsButton)
        toggleButton.setOnClickListener {
            if (!isServiceRunning) {
                checkAndRequestPermissions()
                toggleButton.text = "DEACTIVATE"
                statusText.text = "SYSTEM ACTIVE"
                statusText.setTextColor(Color.parseColor("#E91E63"))
                toggleButton.animate().rotationBy(360f).setDuration(500).start()
            } else {
                stopAssistantService()
                toggleButton.text = "ACTIVATE"
                statusText.text = "SYSTEM READY"
                statusText.setTextColor(Color.parseColor("#CE93D8"))
            }
        }
        sendButton.setOnClickListener {
            myService?.processTranscript(sendText.text.toString())
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        prefs.edit().putLong("main_activity_start_time", SystemClock.elapsedRealtime()).apply()

        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val isRunning = am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ELLIEService::class.qualifiedName
        }
        isServiceRunning = isRunning

        if (isServiceRunning) {
            toggleButton.text = "DEACTIVATE"
            statusText.text = "SYSTEM ACTIVE"
            statusText.setTextColor(Color.parseColor("#E91E63"))
            Intent(this, ELLIEService::class.java).also { intent ->
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            }
        } else {
            toggleButton.text = "ACTIVATE"
            statusText.text = "SYSTEM READY"
            statusText.setTextColor(Color.parseColor("#CE93D8"))
        }
    }
    private fun checkAndRequestPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            perms += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (perms.isEmpty()) {
            ensureOverlayPermission()
        } else {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQ_OVERLAY)
        } else {
            startAssistantService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Settings.canDrawOverlays(this)) {
                startAssistantService()
            } else {
                statusText.text = "Overlay permission is required"
            }
        }
    }

    private fun startAssistantService() {
        Intent(this, ELLIEService::class.java).also { intent ->
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
        isServiceRunning = true
    }

    private fun stopAssistantService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        Intent(this, ELLIEService::class.java).also { intent ->
            stopService(intent)
        }
        isServiceRunning = false
    }
}
