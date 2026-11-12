package com.cloudflare.tunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class QRCodeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qrcode)

        val url     = intent.getStringExtra("tunnel_url") ?: ""
        val tvUrl   = findViewById<TextView>(R.id.tvQrUrl)
        val ivQr    = findViewById<ImageView>(R.id.ivQrCode)
        val btnCopy = findViewById<android.view.View>(R.id.btnCopyUrl)
        val btnBack = findViewById<android.view.View>(R.id.btnBack)
        val tvNoUrl = findViewById<TextView>(R.id.tvNoUrl)

        if (url.isNotEmpty()) {
            tvUrl.text = url
            tvNoUrl.visibility = View.GONE
            ivQr.visibility    = View.VISIBLE
            ivQr.setImageBitmap(generateQr(url, 600))
            btnCopy.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Tunnel URL", url))
                Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
            }
        } else {
            tvUrl.text         = "No active tunnel"
            tvNoUrl.visibility = View.VISIBLE
            ivQr.visibility    = View.GONE
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun generateQr(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN          to 2,
            EncodeHintType.CHARACTER_SET   to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }
}