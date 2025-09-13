package com.example.s3photosync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.datepicker.MaterialDatePicker
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var selectDateButton: Button
    private lateinit var startSyncButton: Button
    private lateinit var statusTextView: TextView

    private var mediaUrisToSync: List<Uri> = listOf()

    // *** RECEIVER TO GET UPDATES FROM SERVICE ***
    private val syncBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == S3UploadService.ACTION_SYNC_COMPLETE) {
                val uploadedCount = intent.getIntExtra(S3UploadService.EXTRA_UPLOADED_COUNT, 0)
                statusTextView.text = "Sync complete! $uploadedCount items uploaded."
                startSyncButton.isEnabled = true // Re-enable button
            }
        }
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                showDatePicker()
            } else {
                statusTextView.text = "Permissions are required to use this app."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectDateButton = findViewById(R.id.selectDateButton)
        startSyncButton = findViewById(R.id.startSyncButton)
        statusTextView = findViewById(R.id.statusTextView)

        selectDateButton.setOnClickListener {
            checkPermissionsAndShowDatePicker()
        }

        startSyncButton.setOnClickListener {
            if (mediaUrisToSync.isNotEmpty()) {
                startUploadService(mediaUrisToSync)
            }
        }

        // *** REGISTER THE RECEIVER ***
        val intentFilter = IntentFilter(S3UploadService.ACTION_SYNC_COMPLETE)
        // ADD THIS LINE IN ITS PLACE
        ContextCompat.registerReceiver(this, syncBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        // *** UNREGISTER THE RECEIVER TO PREVENT LEAKS ***
        unregisterReceiver(syncBroadcastReceiver)
    }

    private fun checkPermissionsAndShowDatePicker() {
        if (requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            showDatePicker()
        } else {
            requestPermissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun showDatePicker() {
        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker().build()
        dateRangePicker.show(supportFragmentManager, "DATE_PICKER_TAG")
        dateRangePicker.addOnPositiveButtonClickListener { dateSelection ->
            val startDate = dateSelection.first
            var endDate = dateSelection.second

            val calendar = Calendar.getInstance()
            calendar.timeInMillis = endDate
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            endDate = calendar.timeInMillis

            statusTextView.text = "Searching..."
            mediaUrisToSync = findMediaUrisForDateRange(startDate, endDate)
            Log.d(TAG, "Found ${mediaUrisToSync.size} media items.")

            if (mediaUrisToSync.isNotEmpty()) {
                statusTextView.text = "Found ${mediaUrisToSync.size} items. Ready to sync."
                startSyncButton.isEnabled = true
            } else {
                statusTextView.text = "No media found for the selected dates."
                startSyncButton.isEnabled = false
            }
        }
    }

    private fun findMediaUrisForDateRange(startDate: Long, endDate: Long): List<Uri> {
        val mediaUris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?) AND ${MediaStore.Files.FileColumns.DATE_ADDED} >= ? AND ${MediaStore.Files.FileColumns.DATE_ADDED} <= ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            (startDate / 1000).toString(),
            (endDate / 1000).toString()
        )
        val queryUri = MediaStore.Files.getContentUri("external")
        contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                mediaUris.add(Uri.withAppendedPath(queryUri, id.toString()))
            }
        }
        return mediaUris
    }

    private fun startUploadService(urisToUpload: List<Uri>) {
        Log.d(TAG, "Starting S3UploadService for ${urisToUpload.size} items.")
        val uriStrings = ArrayList(urisToUpload.map { it.toString() })

        val intent = Intent(this, S3UploadService::class.java).apply {
            putStringArrayListExtra(S3UploadService.EXTRA_URIS, uriStrings)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        statusTextView.text = "Sync started! Check notification for progress."
        startSyncButton.isEnabled = false
        mediaUrisToSync = listOf()
    }
}