package com.example.s3photosync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class S3UploadService : Service() {

    private val TAG = "S3UploadService"
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        const val EXTRA_URIS = "EXTRA_URIS"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETION_NOTIFICATION_ID = 2 // Different ID for the final notification
        private const val CHANNEL_ID = "S3_SYNC_CHANNEL_SERVICE"

        // Action for our broadcast
        const val ACTION_SYNC_COMPLETE = "com.example.s3photosync.SYNC_COMPLETE"
        const val EXTRA_UPLOADED_COUNT = "EXTRA_UPLOADED_COUNT"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uriStrings = intent?.getStringArrayListExtra(EXTRA_URIS)
        if (uriStrings.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val uris = uriStrings.map { Uri.parse(it) }

        val startingNotification = createProgressNotification("Preparing to sync...", 0, uris.size)
        startForeground(NOTIFICATION_ID, startingNotification)

        serviceScope.launch {
            val uploadedCount = performUpload(uris)
            // Send broadcast to MainActivity
            val broadcastIntent = Intent(ACTION_SYNC_COMPLETE).apply {
                putExtra(EXTRA_UPLOADED_COUNT, uploadedCount)
            }
            sendBroadcast(broadcastIntent)
            showCompletionNotification(uploadedCount)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun performUpload(uris: List<Uri>): Int {
        var filesUploaded = 0
        try {
            val credentialsProvider = CognitoCachingCredentialsProvider(
                applicationContext,
                "us-east-1:3c8eae8a-73af-48e3-a6b6-a29d357c0e8c", // Your Pool ID
                Regions.US_EAST_1
            )
            val s3Client = AmazonS3Client(credentialsProvider, Region.getRegion(Regions.US_EAST_1))

            val totalFiles = uris.size
            uris.forEach { uri ->
                val fileExtension = getFileExtension(uri) ?: "bin"
                val tempFileName = "${UUID.randomUUID()}.$fileExtension"
                val file = File(cacheDir, tempFileName)
                try {
                    applicationContext.contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                    val key = "uploads/$tempFileName"
                    val metadata = ObjectMetadata().apply { contentLength = file.length() }
                    s3Client.putObject("image-gallery-private", key, file.inputStream(), metadata)

                    filesUploaded++
                    val notification = createProgressNotification("Uploading $filesUploaded of $totalFiles...", filesUploaded, totalFiles)
                    notificationManager.notify(NOTIFICATION_ID, notification)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload file for URI: $uri", e)
                } finally {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "A critical error occurred in the service.", e)
        }
        return filesUploaded
    }

    private fun showCompletionNotification(uploadedCount: Int) {
        // Stop the foreground service, but keep the notification bar icon for a moment
        // while we show the completion notification. Passing 'false' removes the ongoing flag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        notificationManager.cancel(NOTIFICATION_ID) // Clear the progress notification

        createNotificationChannel() // Ensure channel exists
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sync Complete")
            .setContentText("$uploadedCount items uploaded successfully.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your app's icon
            .setAutoCancel(true) // Dismisses when tapped
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun getFileExtension(uri: Uri): String? {
        val mimeType = contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createProgressNotification(progressText: String, progress: Int, max: Int): android.app.Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Syncing Photos")
            .setContentText(progressText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your app's icon
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "S3 Sync Service"
            val descriptionText = "Shows sync progress"
            val importance = NotificationManager.IMPORTANCE_DEFAULT // Changed to DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply { description = descriptionText }
            notificationManager.createNotificationChannel(channel)
        }
    }
}