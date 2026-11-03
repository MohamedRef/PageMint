package com.mohamedref.pagemint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mohamedref.pagemint.cv.Corner
import com.mohamedref.pagemint.cv.QuadGeometry
import com.mohamedref.pagemint.cv.PageFormat
import com.mohamedref.pagemint.cv.RectificationGeometry
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun CropEditor(
    bitmap: Bitmap,
    corners: List<Corner>,
    automatic: Boolean,
    format: PageFormat,
    safetyBorder: Boolean,
    onChange: (List<Corner>) -> Unit,
    onFormatChange: (PageFormat) -> Unit,
    onSafetyBorderChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onCrop: () -> Unit,
    onBack: () -> Unit,
) {
    val latestCorners by rememberUpdatedState(corners)
    val valid = QuadGeometry.valid(corners)
    val outputPlan = remember(bitmap.width, bitmap.height, corners, format) {
        if (valid) RectificationGeometry.plan(bitmap.width, bitmap.height, corners, format) else null
    }
    Column(Modifier.fillMaxSize()) {
        Text("Adjust corners", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            if (automatic) "Auto crop found a possible page. Check that no text is cut off."
            else "Auto crop was uncertain. Drag the four handles to cover the page.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap.asImageBitmap(), "Uncropped document",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize().pointerInput(bitmap.width, bitmap.height) {
                var dragging: Int? = null
                fun projection(): Triple<Float, Float, Float> {
                    val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                    return Triple(scale, (size.width - bitmap.width * scale) / 2f,
                        (size.height - bitmap.height * scale) / 2f)
                }
                detectDragGestures(
                    onDragStart = { touch ->
                        val (scale, left, top) = projection()
                        val points = latestCorners.map { Offset(left + it.x * bitmap.width * scale, top + it.y * bitmap.height * scale) }
                        dragging = points.indices.minByOrNull { i ->
                            hypot((touch.x - points[i].x).toDouble(), (touch.y - points[i].y).toDouble())
                        }?.takeIf { i ->
                            hypot((touch.x - points[i].x).toDouble(), (touch.y - points[i].y).toDouble()) < 90.dp.toPx()
                        }
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, _ ->
                        val index = dragging
                        if (index != null) {
                            change.consume()
                            val (scale, left, top) = projection()
                            val x = ((change.position.x - left) / (bitmap.width * scale)).coerceIn(0f, 1f)
                            val y = ((change.position.y - top) / (bitmap.height * scale)).coerceIn(0f, 1f)
                            onChange(latestCorners.toMutableList().apply { this[index] = Corner(x, y) })
                        }
                    },
                )
            }) {
                val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                val left = (size.width - bitmap.width * scale) / 2f
                val top = (size.height - bitmap.height * scale) / 2f
                val points = corners.map { Offset(left + it.x * bitmap.width * scale, top + it.y * bitmap.height * scale) }
                val shape = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(shape, Color(0x4433E6B7))
                drawPath(shape, if (valid) Color(0xFF15DBB0) else Color(0xFFFF6A66), style = Stroke(3.dp.toPx()))
                points.forEach { point ->
                    drawCircle(Color.White, radius = 12.dp.toPx(), center = point)
                    drawCircle(Color(0xFF087D70), radius = 7.dp.toPx(), center = point)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Paper proportion", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageFormat.entries.forEach { choice ->
                FilterChip(selected = format == choice, onClick = { onFormatChange(choice) },
                    label = { Text(choice.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = safetyBorder, onCheckedChange = onSafetyBorderChange)
            Text("Keep a small safety border", style = MaterialTheme.typography.bodySmall)
        }
        if (outputPlan != null) {
            Text("Estimated output: ${outputPlan.outputWidth} × ${outputPlan.outputHeight} px",
                style = MaterialTheme.typography.labelSmall)
        }
        if (!valid) Text("Invalid crop: keep four corners in a non-crossing shape.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = onReset) { Text("Reset") }
            Button(onClick = onCrop, enabled = valid, modifier = Modifier.weight(1f)) { Text("Crop & enhance") }
        }
    }
}
