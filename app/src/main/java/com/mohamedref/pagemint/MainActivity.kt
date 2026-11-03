package com.mohamedref.pagemint

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mohamedref.pagemint.cv.Corner
import com.mohamedref.pagemint.cv.DocumentDetector
import com.mohamedref.pagemint.cv.DocumentProcessor
import com.mohamedref.pagemint.cv.PageDetection
import com.mohamedref.pagemint.cv.PageFormat
import com.mohamedref.pagemint.io.ImageIO
import com.mohamedref.pagemint.io.ScanExport
import com.mohamedref.pagemint.ui.CameraCapture
import com.mohamedref.pagemint.ui.CropEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

private enum class Stage { HOME, CAMERA, CROP, RESULT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cvReady = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF087D70))) {
                ScannerApp(cvReady)
            }
        }
    }
}

@Composable
private fun ScannerApp(cvReady: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.HOME) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf<List<Corner>>(emptyList()) }
    var detection by remember { mutableStateOf<PageDetection?>(null) }
    var pageFormat by remember { mutableStateOf(PageFormat.AUTO) }
    var safetyBorder by remember { mutableStateOf(true) }
    var cropped by remember { mutableStateOf<Bitmap?>(null) }
    var enhanced by remember { mutableStateOf<Bitmap?>(null) }
    var showEnhanced by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var cameraAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    fun openImage(uri: Uri) {
        if (busy || !cvReady) return
        busy = true
        status = null
        scope.launch {
            try {
                val (bitmap, found) = withContext(Dispatchers.Default) {
                    val input = ImageIO.load(context, uri)
                    try { Pair(input, DocumentDetector.detect(input)) }
                    catch (error: Throwable) { input.recycle(); throw error }
                }
                // Dispose old images only after the new image is ready.
                source?.takeIf { it !== bitmap }?.recycle()
                cropped?.recycle(); enhanced?.recycle()
                source = bitmap
                corners = found.corners
                detection = found
                pageFormat = PageFormat.AUTO
                cropped = null
                enhanced = null
                stage = Stage.CROP
            } catch (error: Exception) {
                status = "Could not open image: ${error.message.orEmpty()}"
                stage = Stage.HOME
            } finally {
                // Camera captures are app-owned temporary files; imported content is never deleted.
                if (uri.scheme == "file") {
                    val temp = uri.path?.let { path -> java.io.File(path) }
                    if (temp != null && temp.parentFile == context.cacheDir && temp.name.startsWith("pagemint-")) {
                        temp.delete()
                    }
                }
                busy = false
            }
        }
    }

    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let(::openImage)
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraAllowed = it
        if (it) stage = Stage.CAMERA else status = "Camera permission is needed to take photos. Gallery import still works."
    }
    val savePdf = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val output = if (showEnhanced) enhanced else cropped
        if (uri != null && output != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { ScanExport.writePdf(context, uri, output) }
                status = "PDF saved"
            } catch (error: Exception) { status = "Could not save PDF: ${error.message.orEmpty()}" }
            finally { busy = false }
        }
    }
    val saveJpeg = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val output = if (showEnhanced) enhanced else cropped
        if (uri != null && output != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        check(output.compress(Bitmap.CompressFormat.JPEG, 96, stream)) { "Image encoder failed" }
                    } ?: error("Cannot write image")
                }
                status = "Image saved"
            } catch (error: Exception) { status = "Could not save image: ${error.message.orEmpty()}" }
            finally { busy = false }
        }
    }

    BackHandler(stage != Stage.HOME && !busy) {
        stage = when (stage) {
            Stage.RESULT -> Stage.CROP
            else -> Stage.HOME
        }
    }

    Scaffold { inset ->
        Box(Modifier.fillMaxSize().padding(inset)) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PageMint", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.weight(1f))
                    Text("Offline scan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                if (!cvReady) {
                    Text("Document processing is unavailable: OpenCV failed to initialize.", color = MaterialTheme.colorScheme.error)
                }
                when (stage) {
                    Stage.HOME -> {
                        Spacer(Modifier.weight(0.6f))
                        Text("Make every page readable.", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(10.dp))
                        Text("Auto-detect edges, fix perspective and enhance text without uploading your documents.",
                            style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(30.dp))
                        Button(onClick = {
                            if (cameraAllowed) stage = Stage.CAMERA else permission.launch(Manifest.permission.CAMERA)
                        }, enabled = cvReady && !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Text("Scan with camera")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = cvReady && !busy,
                            modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Text("Import a photo")
                        }
                        Spacer(Modifier.weight(1f))
                        Text("No account • No upload • Original image preserved until export",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Stage.CAMERA -> CameraCapture(
                        onCaptured = ::openImage,
                        onBack = { stage = Stage.HOME },
                        onError = { status = it; stage = Stage.HOME },
                    )
                    Stage.CROP -> {
                        val bitmap = source
                        if (bitmap != null && corners.size == 4) {
                            CropEditor(bitmap, corners, detection?.automatic == true,
                                format = pageFormat,
                                safetyBorder = safetyBorder,
                                onChange = { corners = it },
                                onFormatChange = { pageFormat = it },
                                onSafetyBorderChange = { safetyBorder = it },
                                onReset = { corners = detection?.corners ?: emptyList() },
                                onBack = { stage = Stage.HOME },
                                onCrop = {
                                    // Capture a stable selection before the background warp begins.
                                    val cropCorners = corners.toList()
                                    val cropFormat = pageFormat
                                    val keepBorder = safetyBorder
                                    busy = true
                                    status = null
                                    scope.launch {
                                        try {
                                            val (newCrop, newEnhanced) = withContext(Dispatchers.Default) {
                                                val crop = DocumentProcessor.rectify(bitmap, cropCorners, cropFormat, keepBorder)
                                                try { Pair(crop, DocumentProcessor.proEnhance(crop)) }
                                                catch (error: Throwable) { crop.recycle(); throw error }
                                            }
                                            cropped?.recycle(); enhanced?.recycle()
                                            cropped = newCrop
                                            enhanced = newEnhanced
                                            showEnhanced = true
                                            stage = Stage.RESULT
                                        } catch (error: Exception) {
                                            status = "Could not process scan: ${error.message.orEmpty()}"
                                        } finally { busy = false }
                                    }
                                },
                            )
                        }
                    }
                    Stage.RESULT -> {
                        val originalResult = cropped
                        val proResult = enhanced
                        if (originalResult != null && proResult != null) {
                            Text("Review scan", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("Compare the original crop against local Pro Enhance.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilterChip(selected = !showEnhanced, onClick = { showEnhanced = false }, label = { Text("Original crop") })
                                FilterChip(selected = showEnhanced, onClick = { showEnhanced = true }, label = { Text("Pro Enhance") })
                            }
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Image((if (showEnhanced) proResult else originalResult).asImageBitmap(),
                                    "Processed document", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { stage = Stage.CROP }) { Text("Edit crop") }
                                Button(onClick = { savePdf.launch("PageMint-scan.pdf") }, modifier = Modifier.weight(1f)) {
                                    Text("Save PDF")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(onClick = { saveJpeg.launch("PageMint-scan.jpg") }, modifier = Modifier.weight(1f)) {
                                    Text("Save JPG")
                                }
                                OutlinedButton(onClick = {
                                    val bitmap = if (showEnhanced) proResult else originalResult
                                    scope.launch {
                                        busy = true
                                        try {
                                            val share = withContext(Dispatchers.IO) { ScanExport.imageShareIntent(context, bitmap) }
                                            context.startActivity(android.content.Intent.createChooser(share, "Share scan"))
                                        } catch (error: Exception) { status = "Could not share: ${error.message.orEmpty()}" }
                                        finally { busy = false }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Share") }
                            }
                        }
                    }
                }
                if (status != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(status.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = if (status == "PDF saved" || status == "Image saved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            if (busy) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Processing locally…")
                        }
                    }
                }
            }
        }
    }
}
