package com.mohamedref.pagemint.ui

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

@Composable
fun CameraCapture(onCaptured: (Uri) -> Unit, onBack: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var taking by remember { mutableStateOf(false) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(owner, previewView) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (!disposed) {
                try {
                    val ready = future.get()
                    provider = ready
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val still = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    ready.unbindAll()
                    ready.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, still)
                    capture = still
                } catch (exception: Exception) {
                    onError("Camera unavailable: ${exception.message.orEmpty()}")
                }
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            capture = null
            provider?.unbindAll()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Scan a document", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Keep all four corners visible. The original photo is preserved until you crop.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick = {
                    val still = capture ?: return@Button
                    taking = true
                    val photo = File.createTempFile("pagemint-", ".jpg", context.cacheDir)
                    still.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(), mainExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                taking = false
                                onCaptured(Uri.fromFile(photo))
                            }
                            override fun onError(exception: ImageCaptureException) {
                                taking = false
                                photo.delete()
                                onError("Capture failed: ${exception.message.orEmpty()}")
                            }
                        })
                },
                enabled = capture != null && !taking,
                modifier = Modifier.weight(2f),
            ) { Text(if (taking) "Capturing…" else "Capture") }
        }
    }
}
