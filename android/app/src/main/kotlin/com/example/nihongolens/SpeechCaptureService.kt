package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SpeechCaptureService
 *
 * Captures INTERNAL device audio (YouTube, VLC, Chrome, offline videos)
 * via MediaProjection + AudioPlaybackCaptureConfiguration.
 *
 * Audio pipeline:
 *   1. Accumulate 3 seconds of 16kHz mono PCM from AudioRecord
 *   2. Wrap in WAV header
 *   3. POST to whisper_server.py at http://127.0.0.1:8765/transcribe
 *   4. Server returns clean Hindi text (profanity-filtered, translated)
 *   5. Push Hindi text to OverlayService for display
 *
 * Vosk / ModelDownloadService removed entirely — whisper_server.py is
 * pre-installed on the tablet and handles ASR + translation locally.
 */
class SpeechCaptureService : Service() {

    companion object {
        const val CHANNEL_ID        = "speech_capture_channel"
        const val NOTIF_ID          = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var targetLanguage = "hindi"   // always Hindi — kept for Flutter UI compat
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""        // stores source-lang text for Flutter compat
        @Volatile var latestHindi    = ""

        private const val TAG         = "SpeechCapture"
        private const val SAMPLE_RATE = 16_000
        private const val WHISPER_URL = "http://127.0.0.1:8765/transcribe"

        // 3 seconds of audio per chunk (48 000 samples × 2 bytes = 96 000 bytes)
        // Gives whisper enough context for good accuracy without long latency.
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 3
        private const val CHUNK_BYTES   = CHUNK_SAMPLES * 2
    }

    private val mainHandler    = Handler(Looper.getMainLooper())
    private val capturing      = AtomicBoolean(false)
    private var captureThread: Thread?               = null
    private var audioRecord:   AudioRecord?          = null
    private var mediaProjection: MediaProjection?    = null
    private var wakeLock:      PowerManager.WakeLock? = null

    // Single-thread executor: serialise whisper calls so the tablet isn't
    // overwhelmed by concurrent inference jobs.
    private val whisperExecutor = Executors.newSingleThreadExecutor()
    private var lastPushedHindi = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }

        Log.d(TAG, "onCreate — foreground started, wakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "onStartCommand received null intent — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No valid MediaProjection token")
            stopSelf(); return START_NOT_STICKY
        }

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null after getMediaProjection()")
            stopSelf(); return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped externally")
                    mainHandler.post { stopSelf() }
                }
            }, Handler(Looper.getMainLooper()))
        }

        startCapture()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        capturing.set(false)

        captureThread?.interrupt()
        captureThread = null

        try { audioRecord?.stop() }   catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        whisperExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        super.onDestroy()
    }

    // ── Audio capture ─────────────────────────────────────────────────────────

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10 or newer required.")
            stopSelf(); return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection null at capture start")
            OverlayService.updateText("", "Screen capture lost — tap STOP then START again.")
            stopSelf(); return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error: $minBuf")
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        // Buffer must hold at least one full chunk so AudioRecord never stalls
        val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 2)

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.Builder failed: ${e.message}")
            OverlayService.updateText("", "Audio setup failed: ${e.message}")
            stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state=${ar.state} — not initialized")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.")
            stopSelf(); return
        }
        audioRecord = ar

        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio to Hindi…")
        OverlayService.updateText("", "Listening to video audio…")
        Log.d(TAG, "Capture started (bufSize=$bufSize, chunkBytes=$CHUNK_BYTES)")

        captureThread = Thread({
            val chunkBuf = ByteArray(CHUNK_BYTES)
            var chunkPos = 0
            val readBuf  = ByteArray(4096)

            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec  = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)

                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
                if (read <= 0) continue

                // Fill chunk buffer; dispatch when full
                var src = 0
                while (src < read) {
                    val toCopy = minOf(read - src, CHUNK_BYTES - chunkPos)
                    System.arraycopy(readBuf, src, chunkBuf, chunkPos, toCopy)
                    chunkPos += toCopy
                    src      += toCopy

                    if (chunkPos >= CHUNK_BYTES) {
                        val payload = chunkBuf.copyOf(chunkPos)
                        chunkPos = 0
                        if (!whisperExecutor.isShutdown) {
                            whisperExecutor.submit { sendToWhisper(payload) }
                        }
                    }
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply {
            isDaemon = false
            // NORM_PRIORITY: whisper_server.py also needs CPU — don't starve it
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    // ── Whisper HTTP call ─────────────────────────────────────────────────────

    private fun sendToWhisper(pcmBytes: ByteArray) {
        try {
            val wavBytes = pcmToWav(pcmBytes)

            val conn = URL(WHISPER_URL).openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type",   "audio/wav")
            conn.setRequestProperty("Content-Length", wavBytes.size.toString())
            conn.doOutput       = true
            conn.connectTimeout = 5_000    // fail fast if server not running
            conn.readTimeout    = 20_000   // whisper base ≈ 1-3s on Dimensity 7050

            conn.outputStream.use { it.write(wavBytes) }

            val respCode = conn.responseCode
            if (respCode != 200) {
                Log.w(TAG, "Whisper HTTP $respCode")
                return
            }

            val body       = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json       = JSONObject(body)
            val hindiText  = json.optString("text",        "").trim()
            val srcText    = json.optString("source_text", "").trim()
            val lang       = json.optString("language",    "")
            val confidence = json.optDouble("confidence",   0.0)

            // Ignore very short / empty / unchanged results
            if (hindiText.length < 2 || hindiText == lastPushedHindi) return

            Log.d(TAG, "Whisper [$lang / ${(confidence * 100).toInt()}%] → HI: ${hindiText.take(60)}")

            lastPushedHindi = hindiText
            latestOriginal  = srcText
            latestEnglish   = srcText   // Flutter UI reads "english" field; we store source text here
            latestHindi     = hindiText

            mainHandler.post {
                OverlayService.updateText(srcText, hindiText)
                MainActivity.instance?.onTranslation(srcText, hindiText, hindiText)
            }

        } catch (e: Exception) {
            // Silently skip — next chunk will retry automatically
            // Log at WARN only (not ERROR) so LogCat isn't flooded during startup
            Log.w(TAG, "Whisper call: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── PCM → WAV ─────────────────────────────────────────────────────────────

    /**
     * Wraps raw 16-bit mono PCM bytes in a standard WAV (RIFF) container.
     * Whisper / faster-whisper accept WAV natively.
     */
    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels    = 1
        val bitsPerSamp = 16
        val byteRate    = SAMPLE_RATE * channels * bitsPerSamp / 8
        val dataLen     = pcm.size
        val riffChunkSz = dataLen + 36   // RIFF body size (everything after "RIFF" + this int)

        val out = ByteArrayOutputStream(riffChunkSz + 8)
        val dos = DataOutputStream(out)

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeIntLE(riffChunkSz)
        dos.writeBytes("WAVE")

        // fmt sub-chunk
        dos.writeBytes("fmt ")
        dos.writeIntLE(16)                              // sub-chunk size for PCM
        dos.writeShortLE(1)                             // AudioFormat = PCM
        dos.writeShortLE(channels)                      // NumChannels
        dos.writeIntLE(SAMPLE_RATE)                     // SampleRate
        dos.writeIntLE(byteRate)                        // ByteRate
        dos.writeShortLE(channels * bitsPerSamp / 8)   // BlockAlign
        dos.writeShortLE(bitsPerSamp)                   // BitsPerSample

        // data sub-chunk
        dos.writeBytes("data")
        dos.writeIntLE(dataLen)
        dos.write(pcm)
        dos.flush()
        return out.toByteArray()
    }

    // Little-endian helpers for WAV header
    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v         and 0xff)
        write(v shr  8  and 0xff)
        write(v shr 16  and 0xff)
        write(v shr 24  and 0xff)
    }
    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v        and 0xff)
        write(v shr 8  and 0xff)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Internal Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating to Hindi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
