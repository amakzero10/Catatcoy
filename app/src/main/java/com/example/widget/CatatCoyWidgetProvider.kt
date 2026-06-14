package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.FinanceDatabase
import com.example.data.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class CatatCoyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Run database query on a background thread coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = FinanceDatabase.getDatabase(context)
                val dao = db.transactionDao()
                // We get the list of transactions once to compute totals
                val transactionsList = dao.getAllTransactions().first()
                
                val totalIncome = transactionsList.filter { it.type == TransactionType.PEMASUKAN }.sumOf { it.amount }
                val totalExpense = transactionsList.filter { it.type == TransactionType.PENGELUARAN }.sumOf { it.amount }
                val balance = totalIncome - totalExpense

                val isMines = balance < 0
                val isZero = balance == 0.0
                val isOver50Percent = totalIncome > 0.0 && (totalExpense / totalIncome) >= 0.49999

                val statusEmoji = if (isMines) {
                    "😡"
                } else if (isZero) {
                    "☹️"
                } else if (isOver50Percent) {
                    "😐"
                } else {
                    "😊"
                }

                val format = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
                    maximumFractionDigits = 0
                }
                
                val balanceStr = format.format(balance).replace("Rp", "Rp ")
                val incomeStr = format.format(totalIncome).replace("Rp", "Rp ")
                val expenseStr = format.format(totalExpense).replace("Rp", "Rp ")

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.catatcoy_widget_layout)

                    // Fill RemoteViews values
                    views.setTextViewText(R.id.widget_balance_text, balanceStr)
                    views.setTextViewText(R.id.widget_income_text, incomeStr)
                    views.setTextViewText(R.id.widget_expense_text, expenseStr)
                    views.setTextViewText(R.id.widget_status_emoji, statusEmoji)

                    // Change status text colors dynamically
                    views.setTextColor(R.id.widget_balance_text, if (isMines) 0xFFEF4444.toInt() else 0xFF0F172A.toInt())

                    // Clicking the widget launches the main app
                    val intent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_title, pendingIntent)
                    views.setOnClickPendingIntent(R.id.widget_balance_text, pendingIntent)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        fun triggerUpdate(context: Context) {
            val intent = Intent(context, CatatCoyWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, CatatCoyWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
