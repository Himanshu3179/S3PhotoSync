package com.example.s3photosync // Make sure this matches your package name

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferNetworkLossHandler
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // --- AWS Configuration ---
    // IMPORTANT: Replace these values with your own
    private val cognitoIdentityPoolId = "us-east-1:3c8eae8a-73af-48e3-a6b6-a29d357c0e8c"
    private val s3BucketName = "image-gallery-private" // YOUR S3 BUCKET NAME
    private val awsRegion = Regions.US_EAST_1 // YOUR BUCKET REGION

    // --- AWS Clients (will be initialized in onCreate) ---
    private lateinit var s3Client: AmazonS3Client
    private lateinit var transferUtility: TransferUtility

    // --- UI Views ---
    private lateinit var selectPhotosButton: Button
    private lateinit var startSyncButton: Button
    private lateinit var statusTextView: TextView

    // --- State ---
    private var selectedImageUris: List<Uri> = listOf()
    private var totalFilesToUpload = 0
    private var filesUploaded = 0

    private val pickMultipleMedia =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            if (uris.isNotEmpty()) {
                selectedImageUris = uris
                statusTextView.text = "${uris.size} photo(s) selected."
                startSyncButton.isEnabled = true
            } else {
                statusTextView.text = "Status: No photos selected."
                startSyncButton.isEnabled = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Views
        selectPhotosButton = findViewById(R.id.selectPhotosButton)
        startSyncButton = findViewById(R.id.startSyncButton)
        statusTextView = findViewById(R.id.statusTextView)

        // Initialize AWS components
        initializeAws()

        // Set UI Listeners
        selectPhotosButton.setOnClickListener {
            pickMultipleMedia.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }

        startSyncButton.setOnClickListener {
            if (selectedImageUris.isNotEmpty()) {
                startUpload()
            }
        }
    }

    private fun initializeAws() {
        // 1. Initialize Cognito Credentials Provider
        val credentialsProvider = CognitoCachingCredentialsProvider(
            applicationContext,
            cognitoIdentityPoolId,
            awsRegion
        )

        // 2. Initialize S3 Client
        s3Client = AmazonS3Client(credentialsProvider, Region.getRegion(awsRegion))

        // 3. Initialize TransferUtility
        transferUtility = TransferUtility.builder()
            .context(applicationContext)
            .s3Client(s3Client)
            .build()

        // Optional: Reconnect to network automatically if connection is lost
        TransferNetworkLossHandler.getInstance(applicationContext)

        Log.d("MainActivity", "AWS Initialized Successfully")
    }

    private fun startUpload() {
        filesUploaded = 0
        totalFilesToUpload = selectedImageUris.size
        statusTextView.text = "Starting upload of $totalFilesToUpload files..."
        startSyncButton.isEnabled = false // Disable button during upload

        selectedImageUris.forEach { uri ->
            uploadFileToS3(uri)
        }
    }

    private fun uploadFileToS3(uri: Uri) {
        // The Photo Picker returns a content URI, we need to get a real file path
        val file = File(applicationContext.cacheDir, "${UUID.randomUUID()}.jpg")

        try {
            // Copy the selected image content to a temporary file in the app's cache
            val inputStream = contentResolver.openInputStream(uri)
            val outputStream = file.outputStream()
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to create temp file", e)
            statusTextView.text = "Error: Could not read file."
            return
        }

        val key = "uploads/${file.name}" // The "folder" and filename in S3

        val transferObserver = transferUtility.upload(s3BucketName, key, file)

        transferObserver.setTransferListener(object : TransferListener {
            override fun onStateChanged(id: Int, state: TransferState) {
                if (state == TransferState.COMPLETED) {
                    filesUploaded++
                    runOnUiThread {
                        statusTextView.text = "Uploaded $filesUploaded of $totalFilesToUpload"
                        if (filesUploaded == totalFilesToUpload) {
                            statusTextView.text = "Sync Complete! All $totalFilesToUpload files uploaded."
                            startSyncButton.isEnabled = true // Re-enable button
                            file.delete() // Clean up the temp file
                        }
                    }
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                val percentage = (bytesCurrent.toDouble() * 100 / bytesTotal.toDouble()).toInt()
                Log.d("MainActivity", "Uploading file $id: $percentage%")
                // You can update a progress bar here if you want
            }

            override fun onError(id: Int, ex: Exception) {
                Log.e("MainActivity", "Upload Error for file $id", ex)
                runOnUiThread {
                    statusTextView.text = "Error uploading file. Check logs."
                    startSyncButton.isEnabled = true // Re-enable on error
                }
            }
        })
    }
}