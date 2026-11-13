package dev.cloudsim.gateway.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders text as a QR code, one bitmap pixel per module; scale it up without smoothing. */
fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
    val pixels = IntArray(matrix.width * matrix.height) { if (matrix.get(it % matrix.width, it / matrix.width)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
