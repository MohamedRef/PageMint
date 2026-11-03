package com.mohamedref.pagemint.io

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object ScanExport {
    /** Single-page A4 PDF. The bitmap keeps its aspect ratio and is not silently cropped. */
    fun writePdf(context: Context, uri: Uri, bitmap: Bitmap) {
        val pdf = PdfDocument()
        try {
            val width = 595
            val height = 842
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            val canvas: Canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val margin = 22f
            val scale = min((width - 2 * margin) / bitmap.width, (height - 2 * margin) / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (width - drawWidth) / 2
            val top = (height - drawHeight) / 2
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                ?: throw IllegalStateException("Cannot write PDF")
        } finally {
            pdf.close()
        }
    }

    /** Share only a temporary copy, never an unrestricted file path or a public media directory. */
    fun imageShareIntent(context: Context, bitmap: Bitmap): Intent {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(directory, "PageMint-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) { "Could not save image" }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("Scan", uri)
        }
    }
}
