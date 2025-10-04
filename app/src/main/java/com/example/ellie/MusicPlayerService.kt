package com.example.ellie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MusicPlayerService : Service() {
    companion object {
        const val ACTION_PLAY_AUDIO = "com.example.app.action.PLAY_AUDIO"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
        const val EXTRA_IMAGE_URL = "extra_image_url"
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var currentTitle: String = "Unknown"
    private var mediaPlayer: MediaPlayer? = null
    private var currentImageUrl: String? = null

    // 再生位置更新用のHandler
    private val handler = Handler(Looper.getMainLooper())
    private var updatePositionRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "MusicPlayerSession")
        mediaSession.isActive = true

        // MediaSessionのコールバックを設定
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                mediaPlayer?.start()
                updatePlaybackState()
                createNotificationFromUrl(currentImageUrl, true)
                startPositionUpdates()
            }

            override fun onPause() {
                mediaPlayer?.pause()
                updatePlaybackState()
                createNotificationFromUrl(currentImageUrl, false)
                stopPositionUpdates()
            }

            override fun onSeekTo(pos: Long) {
                mediaPlayer?.seekTo(pos.toInt())
                updatePlaybackState()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification(null, false))
        intent?.let {
            val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
            currentImageUrl = imageUrl

            when (it.action) {
                ACTION_PLAY_AUDIO -> {
                    val title = it.getStringExtra(EXTRA_TITLE) ?: "Unknown"
                    currentTitle = title
                    val path = it.getStringExtra(EXTRA_AUDIO_PATH)
                    if (path.isNullOrEmpty()) {
                        Log.e("MusicPlayerService", "AUDIO_PATH is null or empty!")
                    } else {
                        playFromPath(path)
                    }
                }
                "ACTION_PLAY" -> {
                    mediaPlayer?.start()
                    updatePlaybackState()
                    createNotificationFromUrl(imageUrl, true)
                    startPositionUpdates()
                }
                "ACTION_PAUSE" -> {
                    mediaPlayer?.pause()
                    updatePlaybackState()
                    createNotificationFromUrl(imageUrl, false)
                    stopPositionUpdates()
                }
                "ACTION_REWIND" -> {
                    val currentPos = mediaPlayer?.currentPosition ?: 0
                    val newPos = maxOf(0, currentPos - 5000)
                    mediaPlayer?.seekTo(newPos)
                    updatePlaybackState()
                }
                "ACTION_FORWARD" -> {
                    val currentPos = mediaPlayer?.currentPosition ?: 0
                    val duration = mediaPlayer?.duration ?: 0
                    val newPos = minOf(duration, currentPos + 5000)
                    mediaPlayer?.seekTo(newPos)
                    updatePlaybackState()
                }
            }
        }
        return START_STICKY
    }

    private fun playFromPath(path: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).requestAudioFocus(
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setOnAudioFocusChangeListener { }
                        .build()
                )
                val fis = FileInputStream(File(cacheDir, path))
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(fis.fd)

                setOnPreparedListener { mp ->
                    // メディアメタデータを設定
                    val duration = mp.duration.toLong()
                    updateMediaMetadata(duration)

                    mp.start()
                    updatePlaybackState()
                    startForeground(1, createNotification(null, true))
                    startPositionUpdates()
                    fis.close()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e("MusicPlayerService", "MP error what=$what extra=$extra")
                    stopSelf()
                    fis.close()
                    true
                }

                setOnCompletionListener {
                    stopPositionUpdates()
                    updatePlaybackState()
                }

                prepareAsync()
            } catch (e: Exception) {
                Log.e("DEBUG", "FileInputStream error: ${e.message}", e)
            }
        }
    }

    private fun updateMediaMetadata(duration: Long) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Artist")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun updatePlaybackState() {
        val player = mediaPlayer ?: return

        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, player.currentPosition.toLong(), 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_FAST_FORWARD
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        updatePositionRunnable = object : Runnable {
            override fun run() {
                if (mediaPlayer?.isPlaying == true) {
                    updatePlaybackState()
                    createNotificationFromUrl(currentImageUrl, true)
                    handler.postDelayed(this, 1000) // 1秒ごとに更新
                }
            }
        }
        handler.post(updatePositionRunnable!!)
    }

    private fun stopPositionUpdates() {
        updatePositionRunnable?.let {
            handler.removeCallbacks(it)
            updatePositionRunnable = null
        }
    }

    private fun formatTime(timeInMillis: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeInMillis.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeInMillis.toLong()) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun createNotificationFromUrl(imageUrl: String?, isPlaying: Boolean) {
        Thread {
            val bitmap = imageUrl?.let { loadBitmapFromUrl(it) }
            val notification = createNotification(bitmap, isPlaying)
            startForeground(1, notification)
        }.start()
    }

    private fun loadBitmapFromUrl(urlString: String): Bitmap? {
        return try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection()
            connection.connect()
            val input = connection.getInputStream()
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun createNotification(artwork: Bitmap?, isPlaying: Boolean): Notification {
        val channelId = createNotificationChannel()

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent("ACTION_PAUSE"))
        } else {
            NotificationCompat.Action(R.drawable.ic_play_arrow, "Play", getPendingIntent("ACTION_PLAY"))
        }

        val rewindAction = NotificationCompat.Action(R.drawable.ic_rewind, "Rewind", getPendingIntent("ACTION_REWIND"))
        val forwardAction = NotificationCompat.Action(R.drawable.ic_forward, "Forward", getPendingIntent("ACTION_FORWARD"))

        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 再生位置と総再生時間を取得してフォーマット
        val currentPosition = mediaPlayer?.currentPosition ?: 0
        val duration = mediaPlayer?.duration ?: 0
        val timeText = if (duration > 0) {
            "${formatTime(currentPosition)} / ${formatTime(duration)}"
        } else {
            "Artist"
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(currentTitle)
            .setContentText(timeText)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(artwork)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(getPendingIntent("ACTION_PAUSE"))
            )
            .build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlayerService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel(): String {
        val channelId = "music_playback_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        return channelId
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPositionUpdates()
        mediaPlayer?.release()
        mediaSession.release()
        super.onDestroy()
    }
}