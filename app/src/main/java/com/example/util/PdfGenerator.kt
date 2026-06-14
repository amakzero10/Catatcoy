package com.example.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.FinancialTransaction
import com.example.data.TransactionType
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

object PdfGenerator {
    fun generateTransactionReport(
        context: Context,
        reportType: String, // "Harian", "Mingguan", "Bulanan", "Tahunan"
        transactions: List<FinancialTransaction>
    ): File? {
        try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size: 595 x 842 points
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val paint = Paint()
            val textPaint = Paint().apply {
                color = AndroidColor.BLACK
                textSize = 12f
                isAntiAlias = true
            }

            val currencyFormatter = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
                maximumFractionDigits = 0
            }

            val dateFormatter = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))

            // 1. Draw Title Header Banner
            paint.color = AndroidColor.parseColor("#008080") // Teal / Tosca theme accent
            canvas.drawRect(0f, 0f, 595f, 90f, paint)

            paint.color = AndroidColor.WHITE
            paint.textSize = 24f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("catat coy", 40f, 50f, paint)

            paint.textSize = 13f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            canvas.drawText("Laporan Keuangan - Periode $reportType", 40f, 72f, paint)

            val todayStr = dateFormatter.format(System.currentTimeMillis())
            canvas.drawText("Tanggal Cetak: $todayStr", 400f, 50f, paint)

            // Revert background paint color
            paint.color = AndroidColor.BLACK

            // 2. Summary Dashboard Box
            val startYSummary = 120f
            paint.color = AndroidColor.parseColor("#F4F6F6")
            canvas.drawRoundRect(40f, startYSummary, 555f, startYSummary + 85f, 12f, 12f, paint)

            val totalIncome = transactions.filter { it.type == TransactionType.PEMASUKAN }.sumOf { it.amount }
            val totalExpense = transactions.filter { it.type == TransactionType.PENGELUARAN }.sumOf { it.amount }
            val netBalance = totalIncome - totalExpense

            textPaint.apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 10f
                color = AndroidColor.parseColor("#7F8C8D")
            }
            canvas.drawText("TOTAL PEMASUKAN", 60f, startYSummary + 30f, textPaint)
            canvas.drawText("TOTAL PENGELUARAN", 230f, startYSummary + 30f, textPaint)
            canvas.drawText("SALDO SEKARANG (NET)", 400f, startYSummary + 30f, textPaint)

            textPaint.textSize = 13f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            // Pemasukan is vibrant blue tosca
            textPaint.color = AndroidColor.parseColor("#00897B")
            canvas.drawText(currencyFormatter.format(totalIncome), 60f, startYSummary + 58f, textPaint)

            // Pengeluaran is bold red
            textPaint.color = AndroidColor.parseColor("#C62828")
            canvas.drawText(currencyFormatter.format(totalExpense), 230f, startYSummary + 58f, textPaint)

            // Balance colored dependently
            textPaint.color = if (netBalance >= 0) AndroidColor.parseColor("#00897B") else AndroidColor.parseColor("#C62828")
            canvas.drawText(currencyFormatter.format(netBalance), 400f, startYSummary + 58f, textPaint)

            // 3. Transactions Table Header
            val tableStartY = 240f
            paint.color = AndroidColor.parseColor("#E0F2F1") // Light teal tint
            canvas.drawRect(40f, tableStartY, 555f, tableStartY + 28f, paint)

            textPaint.apply {
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = AndroidColor.parseColor("#004D40")
            }
            canvas.drawText("Tanggal", 48f, tableStartY + 18f, textPaint)
            canvas.drawText("Platform", 135f, tableStartY + 18f, textPaint)
            canvas.drawText("Kategori", 205f, tableStartY + 18f, textPaint)
            canvas.drawText("Deskripsi / Catatan", 295f, tableStartY + 18f, textPaint)
            canvas.drawText("Jumlah (Rp)", 465f, tableStartY + 18f, textPaint)

            // Draw line rows
            var currentY = tableStartY + 28f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.textSize = 9.5f

            transactions.take(24).forEach { trans ->
                paint.color = AndroidColor.parseColor("#E5E8E8")
                paint.strokeWidth = 1f
                canvas.drawLine(40f, currentY, 555f, currentY, paint)

                currentY += 21f
                if (currentY > 790f) return@forEach // Prevent overflowing A4 page limits

                textPaint.color = AndroidColor.parseColor("#34495E")
                canvas.drawText(dateFormatter.format(trans.dateMillis), 48f, currentY - 6f, textPaint)

                // Render type correctly with distinct color formatting
                if (trans.type == TransactionType.PEMASUKAN) {
                    textPaint.color = AndroidColor.parseColor("#00897B")
                    canvas.drawText("Pemasukan", 135f, currentY - 6f, textPaint)
                } else {
                    textPaint.color = AndroidColor.parseColor("#C62828")
                    canvas.drawText("Pengeluaran", 135f, currentY - 6f, textPaint)
                }

                textPaint.color = AndroidColor.parseColor("#2C3E50")
                canvas.drawText(trans.category, 205f, currentY - 6f, textPaint)

                // Note with safety cap limit
                val noteStr = if (trans.note.length > 28) trans.note.take(25) + "..." else trans.note
                canvas.drawText(noteStr, 295f, currentY - 6f, textPaint)

                val amountStr = currencyFormatter.format(trans.amount)
                if (trans.type == TransactionType.PEMASUKAN) {
                    textPaint.color = AndroidColor.parseColor("#00897B")
                    canvas.drawText("+ " + amountStr, 465f, currentY - 6f, textPaint)
                } else {
                    textPaint.color = AndroidColor.parseColor("#C62828")
                    canvas.drawText("- " + amountStr, 465f, currentY - 6f, textPaint)
                }
            }

            // Footer Section
            val footerY = 815f
            paint.color = AndroidColor.parseColor("#BDC3C7")
            paint.strokeWidth = 1f
            canvas.drawLine(40f, footerY - 10f, 555f, footerY - 10f, paint)

            textPaint.apply {
                textSize = 9f
                color = AndroidColor.parseColor("#7F8C8D")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            }
            canvas.drawText("Laporan keuangan valid dibuat oleh aplikasi catat coy.", 40f, footerY + 5f, textPaint)
            canvas.drawText("Total item: ${transactions.size}", 465f, footerY + 5f, textPaint)

            pdfDocument.finishPage(page)

            val file = File(context.cacheDir, "laporan_${reportType.lowercase()}_catatcoy.pdf")
            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)

            outputStream.flush()
            outputStream.close()
            pdfDocument.close()

            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
