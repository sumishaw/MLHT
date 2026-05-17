package com.example.nihongolens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * MainActivity
 *
 * Replaces Vosk / ModelDownloadService with a whisper_server.py health check.
 * The Flutter UI calls the same MethodChannel API as before — only the model
 * download methods are remapped to whisper server readiness checks so the
 * existing Flutter UI (which shows "Speech Model Ready") keeps working.
 *
 * Method mapping (old → new):
 *   isModelReady        → GET http://127.0.0.1:8765/ready
 *   getModelStatus      → "ready" / "not_downloaded" based on whisper health
 *   startModelDownload  → triggers a background whisper health check;
 *                          fires onModelReady when server responds
 *   onDownloadProgress  → not sent (whisper is pre-installed, no download needed)
 *   onModelReady        → sent when whisper /ready returns true
 *   onModelError        → sent when whisper server is unreachable
 */
class MainActivity : FlutterActivity() {

    companion object {
        @Volatile var instance: MainActivity? = null

        private const val REQ_MEDIA_PROJECTION = 200
        private const val REQ_AUDIO_PERMISSION  = 100
        private const val TAG                   = "MainActivity"

        private const val WHISPER_HEALTH_URL = "http://127.0.0.1:8765/ready"
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    @Volatile private var pendingProjectionResult: MethodChannel.Result? = null

    // Background executor for whisper health check (non-blocking)
    private val healthExecutor = Executors.newSingleThreadExecutor()

    // ── Flutter method channel ─────────────────────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {

                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                "hasAudioPermission" ->
                    result.success(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                    )

                "requestAudioPermission" ->
                    requestAudioThenProjection(result)

                // Accessibility not required for internal-audio capture
                "checkAccessibilityEnabled" -> result.success(true)
                "openAccessibilitySettings" -> result.success(true)

                // ── Whisper server readiness (replaces Vosk model checks) ────

                "isModelReady" -> checkWhisperReady { ready ->
                    runOnUiThread { result.success(ready) }
                }

                "getModelStatus" -> checkWhisperReady { ready ->
                    runOnUiThread {
                        result.success(if (ready) "ready" else "not_downloaded")
                    }
                }

                /**
                 * Flutter calls startModelDownload on first launch (or retry).
                 * Instead of downloading, we check if whisper_server.py is running
                 * and fire onModelReady / onModelError so the Flutter UI updates.
                 */
                "startModelDownload" -> {
                    result.success(true)   // acknowledge immediately
                    checkAndNotifyWhisperReady()
                }

                // ── Overlay ──────────────────────────────────────────────────

                "startOverlay" -> {
                    val i = Intent(this, OverlayService::class.java)
                    startForegroundServiceCompat(i)
                    result.success(true)
                }

                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(true)
                }

                // ── Speech capture ────────────────────────────────────────────

                "startSpeechCapture" ->
                    requestAudioThenProjection(result)

                "stopSpeechCapture" -> {
                    stopService(Intent(this, SpeechCaptureService::class.java))
                    result.success(true)
                }

                "isSpeechCaptureRunning" ->
                    result.success(SpeechCaptureService.isRunning)

                "setTargetLanguage" -> {
                    // Always Hindi — kept for Flutter UI compatibility
                    SpeechCaptureService.targetLanguage = "hindi"
                    result.success(true)
                }

                "getLatestTranslation" ->
                    result.success(mapOf(
                        "original" to SpeechCaptureService.latestOriginal,
                        "english"  to SpeechCaptureService.latestEnglish,   // source-lang text
                        "hindi"    to SpeechCaptureService.latestHindi
                    ))

                else -> result.notImplemented()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Check whisper readiness on launch so the Flutter UI shows the
        // correct model status without waiting for the user to tap anything.
        checkAndNotifyWhisperReady()
    }

    override fun onResume() {
        super.onResume()
        instance = this
    }

    override fun onDestroy() {
        pendingProjectionResult?.success(false)
        pendingProjectionResult = null
        healthExecutor.shutdownNow()
        instance = null
        super.onDestroy()
    }

    // ── Whisper server health checks ──────────────────────────────────────────

    /**
     * Asynchronously check if whisper_server.py is running.
     * [onResult] is called on the executor thread with true/false.
     */
    private fun checkWhisperReady(onResult: (Boolean) -> Unit) {
        healthExecutor.submit {
            val ready = try {
                val conn = URL(WHISPER_HEALTH_URL).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) {
                false
            }
            onResult(ready)
        }
    }

    /**
     * Check whisper readiness and broadcast the result to the Flutter UI
     * via the same callbacks that ModelDownloadService used to send.
     * This keeps the Flutter UI working without any Dart-side changes.
     */
    private fun checkAndNotifyWhisperReady() {
        checkWhisperReady { ready ->
            runOnUiThread {
                if (ready) {
                    Log.d(TAG, "whisper_server.py is ready")
                    methodChannel?.invokeMethod("onModelReady", null)
                } else {
                    Log.w(TAG, "whisper_server.py not reachable on port 8765")
                    methodChannel?.invokeMethod(
                        "onModelError",
                        mapOf("message" to
                            "Whisper server not running.\n" +
                            "Start it with:\n  python3 whisper_server.py\n" +
                            "Then tap RETRY.")
                    )
                }
            }
        }
    }

    // ── Permission + projection flow ──────────────────────────────────────────

    private fun requestAudioThenProjection(result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) {
            result.success(false); return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaProjection(result)
        } else {
            deliverPendingFailure()
            pendingProjectionResult = result
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO_PERMISSION
            )
        }
    }

    private fun requestMediaProjection(result: MethodChannel.Result) {
        deliverPendingFailure()
        pendingProjectionResult = result
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "createScreenCaptureIntent failed: ${e.message}")
            pendingProjectionResult = null
            result.success(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION) {
            val pending = pendingProjectionResult
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pending != null) {
                    pendingProjectionResult = null
                    requestMediaProjection(pending)
                }
            } else {
                pendingProjectionResult = null
                pending?.success(false)
            }
        }
    }

    @Deprecated("Required for API compatibility below 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_MEDIA_PROJECTION) {
            val pending = pendingProjectionResult
            pendingProjectionResult = null

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection granted — starting SpeechCaptureService")
                val i = Intent(this, SpeechCaptureService::class.java).apply {
                    putExtra(SpeechCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(SpeechCaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundServiceCompat(i)
                pending?.success(true)
            } else {
                Log.w(TAG, "MediaProjection denied (resultCode=$resultCode)")
                pending?.success(false)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun deliverPendingFailure() {
        val stale = pendingProjectionResult
        if (stale != null) {
            pendingProjectionResult = null
            try { stale.success(false) } catch (_: Exception) {}
        }
    }

    /** Called from SpeechCaptureService to push a translation to the Flutter UI. */
    fun onTranslation(original: String, english: String, hindi: String) {
        runOnUiThread {
            methodChannel?.invokeMethod("onTranslation", mapOf(
                "original" to original,
                "english"  to english,
                "hindi"    to hindi
            ))
        }
    }
}
