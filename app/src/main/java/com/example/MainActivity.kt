package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.FinanceDatabase
import com.example.data.FinancialTransaction
import com.example.data.TransactionType
import com.example.ui.theme.MyApplicationTheme
import com.example.util.PdfGenerator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

// Custom Color Constants matching client request
val ToscaColor = Color(0xFF00ADB5)      // For Pemasukan (Biru Tosca)
val RedExpenseColor = Color(0xFFEF5350)  // For Pengeluaran (Merah)
val SlateDark = Color(0xFF0F172A)
val CardBackgroundLight = Color(0xFFF8FAFC)
val CardBackgroundDark = Color(0xFF1E293B)

// Data structure for the 7 Days visual chart recap
data class DailyRecap(
    val dayName: String,
    val dateMillis: Long,
    val income: Double,
    val expense: Double
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

class FinanceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = FinanceDatabase.getDatabase(application)
    private val repository = com.example.data.TransactionRepository(db.transactionDao())

    val allTransactions: StateFlow<List<FinancialTransaction>> = repository.allTransactions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // No preloaded seed data. Started cleanly from 0!
    }

    private fun getPastTimeMillis(daysOffset: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysOffset)
        }.timeInMillis
    }

    fun addTransaction(amount: Double, note: String, category: String, type: TransactionType) {
        viewModelScope.launch {
            val trans = FinancialTransaction(
                amount = amount,
                note = note.ifBlank { "Transaksi $type" },
                category = category.ifBlank { "Umum" },
                dateMillis = System.currentTimeMillis(),
                type = type
            )
            repository.insert(trans)
            com.example.widget.CatatCoyWidgetProvider.triggerUpdate(getApplication())
        }
    }

    fun updateTransaction(id: Long, amount: Double, note: String, category: String, type: TransactionType, dateMillis: Long) {
        viewModelScope.launch {
            val trans = FinancialTransaction(
                id = id,
                amount = amount,
                note = note.ifBlank { "Transaksi $type" },
                category = category.ifBlank { "Umum" },
                dateMillis = dateMillis,
                type = type
            )
            repository.insert(trans)
            com.example.widget.CatatCoyWidgetProvider.triggerUpdate(getApplication())
        }
    }

    fun deleteTransaction(transaction: FinancialTransaction) {
        viewModelScope.launch {
            repository.delete(transaction)
            com.example.widget.CatatCoyWidgetProvider.triggerUpdate(getApplication())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: FinanceViewModel = viewModel()
) {
    val context = LocalContext.current
    val transactions by viewModel.allTransactions.collectAsState()

    // Calculation states
    val totalIncome = remember(transactions) {
        transactions.filter { it.type == TransactionType.PEMASUKAN }.sumOf { it.amount }
    }
    val totalExpense = remember(transactions) {
        transactions.filter { it.type == TransactionType.PENGELUARAN }.sumOf { it.amount }
    }
    val balance = totalIncome - totalExpense

    // Dialog sheets states
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedTypeForAdd by remember { mutableStateOf(TransactionType.PEMASUKAN) }
    var showReportDialog by remember { mutableStateOf(false) }
    var selectedReportPeriod by remember { mutableStateOf("Mingguan") } // "Harian", "Mingguan", "Bulanan", "Tahunan"
    var showEditDialog by remember { mutableStateOf(false) }
    var selectedTransactionToEdit by remember { mutableStateOf<FinancialTransaction?>(null) }

    val creamGradient = remember {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFFDFBF7), // Cozy soft light cream
                Color(0xFFFAF5E6), // Warm vanilla cream
                Color(0xFFF3ECE0)  // Soft deluxe beige cream
            )
        )
    }

    val isNormalFriendly = balance > 0.0 && (totalIncome == 0.0 || (totalExpense / totalIncome) < 0.49999)
    val expenseAccentColor = if (isNormalFriendly) ToscaColor else RedExpenseColor

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(creamGradient) // Beautiful cream background
    ) {
        // --- 1. Top Core App Header ---
        HeaderSection(isMines = balance < 0)

        // Core Balance, Stats and lists scrollable column
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Balance Card styled as standard Bento Slate Box
            item {
                BalanceSummaryCard(
                    balance = balance,
                    totalIncome = totalIncome,
                    totalExpense = totalExpense
                )
            }

            // --- 2. Interactive Pemasukan VS Pengeluaran Displays ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Pengeluaran Bento Card Display - Left Column
                    SummaryBox(
                        title = "Pengeluaran",
                        amount = totalExpense,
                        accentColor = expenseAccentColor,
                        ratio = if ((totalIncome + totalExpense) > 0) (totalExpense / (totalIncome + totalExpense)).toFloat() else 0.4f,
                        trackColor = Color(0xFFFFEBEE),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("summary_expense_card")
                    )

                    // Pemasukan Bento Card Display - Right Column
                    SummaryBox(
                        title = "Pemasukan",
                        amount = totalIncome,
                        accentColor = ToscaColor,
                        ratio = if ((totalIncome + totalExpense) > 0) (totalIncome / (totalIncome + totalExpense)).toFloat() else 0.6f,
                        trackColor = Color(0xFFE0F2F1),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("summary_income_card")
                    )
                }
            }

            // --- 3. Center Section: Sleek Dark Bento Action Pill Panel ---
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(36.dp))
                            .background(Color.White.copy(alpha = 0.45f)) // glassmorphic clear bento container
                            .border(
                                width = 1.2.dp,
                                color = Color.White.copy(alpha = 0.8f), // glass border highlight
                                shape = RoundedCornerShape(36.dp)
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Underneath scrollable sub-tabs
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("Harian", "Mingguan", "Bulanan").forEach { tab ->
                                val isActive = selectedReportPeriod == tab
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (isActive) Color.White.copy(alpha = 0.75f) 
                                            else Color.Transparent
                                        )
                                        .border(
                                            width = if (isActive) 1.dp else 0.dp,
                                            color = if (isActive) Color.White.copy(alpha = 0.9f) else Color.Transparent,
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                        .clickable {
                                            selectedReportPeriod = tab
                                            showReportDialog = true
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isActive) Color(0xFF0F172A) else Color(0xFF576F87) // glossy black for active, sleek gray-blue for inactive
                                    )
                                }
                            }
                        }

                        // Circular Float Center styled like luxurious glowing "clear glass" button
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.75f)) // frosted backdrop
                                .border(
                                    width = 1.5.dp,
                                    color = Color.White.copy(alpha = 0.9f), // glassy outline reflection
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedTypeForAdd = TransactionType.PEMASUKAN
                                    showAddDialog = true
                                }
                                .testTag("bento_add_launcher"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Tambah Transaksi Baru",
                                tint = Color(0xFF0F172A), // glossy black
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Quick PDF Action Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.65f)) // clear frosted glass
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable {
                                    val filtered = getTransactionsForPeriod(transactions, selectedReportPeriod)
                                    val pdfFile = PdfGenerator.generateTransactionReport(context, selectedReportPeriod, filtered)
                                    if (pdfFile != null) {
                                        sharePdf(context, pdfFile)
                                    } else {
                                        Toast.makeText(context, "Gagal mengekspor PDF", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("bento_pdf_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "PDF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF0F172A) // glossy black
                            )
                        }
                    }
                }
            }

            // Quick transaction type direct logs buttons row (Pengeluaran merah / Pemasukan tosca)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            selectedTypeForAdd = TransactionType.PEMASUKAN
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ToscaColor.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                    ) {
                        Text(
                            text = "+ Catat Pemasukan",
                            color = ToscaColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            selectedTypeForAdd = TransactionType.PENGELUARAN
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedExpenseColor.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                    ) {
                        Text(
                            text = "+ Catat Pengeluaran",
                            color = RedExpenseColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // --- 4 & 5. Combined Bottom Large Bento Container containing the 7 Days visual graph,
            // Recents headers, Scrollable history lists & summary micro metrics ---
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(36.dp))
                            .background(Color.White)
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recap 7 Hari Terakhir",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = "Okt 18 - Okt 24",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val sevenDayRecapList = remember(transactions) {
                            calculate7DayRecap(transactions)
                        }
                        // Visual bar chart rendering
                        SevenDayRecapGraphSection(recapData = sevenDayRecapList)

                        Spacer(modifier = Modifier.height(24.dp))

                        // Transaction list items block
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Aktivitas Terbaru",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = "${transactions.size} Transaksi",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (transactions.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Belum ada catatan aktivitas.",
                                        fontSize = 12.sp,
                                        color = Color.LightGray
                                    )
                                }
                            } else {
                                transactions.take(6).forEach { item ->
                                    TransactionItemRow(
                                        transaction = item,
                                        onEditClick = {
                                            selectedTransactionToEdit = item
                                            showEditDialog = true
                                        },
                                        onDeleteClick = { viewModel.deleteTransaction(item) }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = Color(0xFFF1F5F9))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Bottom Summary Micro Data metrics from design template
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "LAPORAN",
                                    fontSize = 9.sp,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (balance >= 0) "Sehat" else "Defisit",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (balance >= 0) Color(0xFF00ADB5) else Color(0xFFEF5350)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(Color(0xFFF1F5F9))
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "TABUNGAN",
                                    fontSize = 9.sp,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (balance >= 0) "+12%" else "-5%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF3B82F6)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Interactive Popups / Sheet dialog builders ---

    // A. Dialog popup for inserting Pemasukan & Pengeluaran
    if (showAddDialog) {
        AddTransactionDialog(
            type = selectedTypeForAdd,
            onDismiss = { showAddDialog = false },
            onSave = { amount, note, category ->
                viewModel.addTransaction(amount, note, category, selectedTypeForAdd)
                showAddDialog = false
                Toast.makeText(context, "Log Transaksi berhasil disimpan!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // B. Dialog popup to view the selected filtered Period report and generate PDF
    if (showReportDialog) {
        val filteredTransactions = remember(transactions, selectedReportPeriod) {
            getTransactionsForPeriod(transactions, selectedReportPeriod)
        }
        ReportOverviewDialog(
            period = selectedReportPeriod,
            transactions = filteredTransactions,
            onDismiss = { showReportDialog = false },
            onExportPdf = {
                val pdfFile = PdfGenerator.generateTransactionReport(context, selectedReportPeriod, filteredTransactions)
                if (pdfFile != null) {
                    sharePdf(context, pdfFile)
                } else {
                    Toast.makeText(context, "Gagal melahirkan file PDF", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showEditDialog) {
        selectedTransactionToEdit?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                onDismiss = {
                    showEditDialog = false
                    selectedTransactionToEdit = null
                },
                onSave = { amount, note, category ->
                    viewModel.updateTransaction(
                        id = transaction.id,
                        amount = amount,
                        note = note,
                        category = category,
                        type = transaction.type,
                        dateMillis = transaction.dateMillis
                    )
                    showEditDialog = false
                    selectedTransactionToEdit = null
                    Toast.makeText(context, "Transaksi berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }


}

// Custom Header Component conforming to premium aesthetic
@Composable
fun HeaderSection(isMines: Boolean) {
    val backgroundColor = if (isMines) Color(0xFF4C0E17) else Color.Transparent
    val textColor = if (isMines) Color.White else Color(0xFF1E293B)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(top = 18.dp, bottom = 12.dp, start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Visual Logo placeholder as glassmorphic clear with a glossy black text
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.45f)) // glassmorphic clear backdrop
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.7f), // glossy glass reflection rim
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "10",
                color = Color(0xFF0F172A), // glossy deep black
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = "Catat Coy",
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }
    }
}

@Composable
fun BalanceSummaryCard(balance: Double, totalIncome: Double, totalExpense: Double) {
    val isMines = balance < 0
    val isZero = balance == 0.0
    val isOver50Percent = totalIncome > 0.0 && (totalExpense / totalIncome) >= 0.49999

    val statusText: String
    val statusColor: Color
    val statusEmoji: String

    if (isMines) {
        statusText = "WOIII KOK MINESSSS"
        statusColor = Color(0xFFEF4444) // Red for mines warning (emoji angry)
        statusEmoji = "😡"
    } else if (isZero) {
        statusText = "Finansial Bokek"
        statusColor = Color(0xFFEF4444) // Red for zero warning (emoji sad)
        statusEmoji = "☹️"
    } else if (isOver50Percent) {
        statusText = "Kondisi Finansial Normal"
        statusColor = Color(0xFFEAB308) // Yellow for 50%+ expense (emoji flat)
        statusEmoji = "😐"
    } else {
        statusText = "Kondisi Finansial Sehat"
        statusColor = ToscaColor // Tosca for normal healthy condition (emoji smiley)
        statusEmoji = "😊"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = SlateDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "SALDO SEKARANG (NET)",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                Spacer(modifier = Modifier.height(6.dp))

                val format = remember {
                    NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
                        maximumFractionDigits = 0
                    }
                }
                AnimatedContent(
                    targetState = balance,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { height -> height } + fadeIn() togetherWith
                                    slideOutVertically { height -> -height } + fadeOut())
                        } else {
                            (slideInVertically { height -> -height } + fadeIn() togetherWith
                                    slideOutVertically { height -> height } + fadeOut())
                        }.using(
                            SizeTransform(clip = false)
                        )
                    },
                    label = "BalanceAnimation"
                ) { animatedBalance ->
                    Text(
                        text = format.format(animatedBalance).replace("Rp", "Rp "),
                        color = if (animatedBalance >= 0) ToscaColor else RedExpenseColor,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Elegant "clear glass" frosted container with a dynamic shining Emoji status inside
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f)) // frosty clear glass backplate
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.25f), // polished clear glass rim
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusEmoji,
                    fontSize = 26.sp
                )
            }
        }
    }
}

@Composable
fun SummaryBox(
    title: String,
    amount: Double,
    accentColor: Color,
    ratio: Float,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(130.dp),
        shape = RoundedCornerShape(28.dp), // heavily rounded bento corners
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                val format = remember {
                    NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
                        maximumFractionDigits = 0
                    }
                }
                AnimatedContent(
                    targetState = amount,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { height -> height } + fadeIn() togetherWith
                                    slideOutVertically { height -> -height } + fadeOut())
                        } else {
                            (slideInVertically { height -> -height } + fadeIn() togetherWith
                                    slideOutVertically { height -> height } + fadeOut())
                        }.using(
                            SizeTransform(clip = false)
                        )
                    },
                    label = "AmountAnimation"
                ) { animatedAmount ->
                    Text(
                        text = format.format(animatedAmount).replace("Rp", "Rp "),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor, // Red for Pengeluaran / Tosca for Pemasukan
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Beautiful mini progress ratio tracks from bento specs
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = ratio.coerceIn(0.04f, 1.0f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
    }
}

// 7 Days Custom Graphical Component drawing side-by-side indicator bar charts
@Composable
fun SevenDayRecapGraphSection(recapData: List<DailyRecap>) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Rekap Finansial 7 Hari Terakhir",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Let's draw side-by-side bar charts
        val maxAmount = remember(recapData) {
            val highest = recapData.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0.0
            if (highest == 0.0) 100000.0 else highest
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            recapData.forEach { data ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    // Drawing the dual bars using a simple canvas inside
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Row(
                            modifier = Modifier.fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            // Pemasukan Bar (Tosca)
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .fillMaxHeight(fraction = (data.income / maxAmount).toFloat().coerceIn(0.01f, 1f))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(ToscaColor)
                            )

                            // Pengeluaran Bar (Red)
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .fillMaxHeight(fraction = (data.expense / maxAmount).toFloat().coerceIn(0.01f, 1f))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(RedExpenseColor)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = data.dayName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Legend indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ToscaColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Pemasukan",
                fontSize = 11.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(20.dp))

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(RedExpenseColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Pengeluaran",
                fontSize = 11.sp,
                color = Color(0xFF64748B),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// Recaps transactions of the last 7 days from today back to 6 days ago
fun calculate7DayRecap(transactions: List<FinancialTransaction>): List<DailyRecap> {
    val recaps = mutableListOf<DailyRecap>()
    val simpleDateFormatVal = SimpleDateFormat("EEE", Locale("id", "ID"))

    val calendarByDate = transactions.groupBy {
        val cal = Calendar.getInstance().apply { timeInMillis = it.dateMillis }
        "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    // From 6 days ago to today (7 days in total)
    for (i in 6 downTo 0) {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -i)
        val key = "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}-${c.get(Calendar.DAY_OF_MONTH)}"
        
        val listForDay = calendarByDate[key] ?: emptyList()
        val incomeVal = listForDay.filter { it.type == TransactionType.PEMASUKAN }.sumOf { it.amount }
        val expenseVal = listForDay.filter { it.type == TransactionType.PENGELUARAN }.sumOf { it.amount }
        
        recaps.add(
            DailyRecap(
                dayName = simpleDateFormatVal.format(c.time),
                dateMillis = c.timeInMillis,
                income = incomeVal,
                expense = expenseVal
            )
        )
    }
    return recaps
}

// Fetch period based reports
fun getTransactionsForPeriod(transactions: List<FinancialTransaction>, period: String): List<FinancialTransaction> {
    val calNow = Calendar.getInstance()
    val nowMillis = calNow.timeInMillis

    return transactions.filter { trans ->
        val calTrans = Calendar.getInstance().apply { timeInMillis = trans.dateMillis }
        when (period) {
            "Harian" -> {
                calTrans.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                calTrans.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)
            }
            "Mingguan" -> {
                // Within past 7 days
                val diffTime = nowMillis - trans.dateMillis
                val diffDays = diffTime / (1000 * 60 * 60 * 24)
                diffDays in 0..7
            }
            "Bulanan" -> {
                calTrans.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                calTrans.get(Calendar.MONTH) == calNow.get(Calendar.MONTH)
            }
            "Tahunan" -> {
                calTrans.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)
            }
            else -> true
        }
    }
}

// Single list transaction element
@Composable
fun TransactionItemRow(
    transaction: FinancialTransaction,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fmt = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
        maximumFractionDigits = 0
    }
    val dateFmt = SimpleDateFormat("dd MMM, HH:mm", Locale("id", "ID"))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF8FAFC)) // Subtle elegant gray backgrounds for bento list items
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tiny icon box displaying category letter as a dark box ("kotak warna gelap") with clear glass representation
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E293B)) // Kotak warna gelap bento style
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f), // crisp glass frame
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = transaction.category.take(1).uppercase(),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                // Colored clear glass glowing colors
                color = if (transaction.type == TransactionType.PEMASUKAN) {
                    Color(0xFF80ECE1).copy(alpha = 0.95f) // glowing clear glass teal-tosca
                } else {
                    Color(0xFFFFADAD).copy(alpha = 0.95f) // glowing clear glass pastel red-coral
                }
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = transaction.note,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = transaction.category,
                    fontSize = 11.sp,
                    color = Color(0xFF475569),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "•",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
                Text(
                    text = dateFmt.format(transaction.dateMillis),
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            val styledAmount = fmt.format(transaction.amount).replace("Rp", "Rp ")
            Text(
                text = if (transaction.type == TransactionType.PEMASUKAN) "+ $styledAmount" else "- $styledAmount",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { onEditClick() },
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("edit_item_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Transaksi",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = { onDeleteClick() },
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("delete_item_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Hapus Transaksi",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Interactive Popup Dialog to add Income or Expense
@Composable
fun AddTransactionDialog(
    type: TransactionType,
    onDismiss: () -> Unit,
    onSave: (amount: Double, note: String, category: String) -> Unit
) {
    var amountInput by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }

    // Quick selectable catalog suggestions for better UX
    val categories = remember(type) {
        if (type == TransactionType.PEMASUKAN) {
            listOf("Gaji", "Sampingan", "Investasi", "Hadiah", "Tabungan", "Lainnya")
        } else {
            listOf("Makanan", "Transportasi", "Belanja", "Kos", "Utilitas", "Lainnya")
        }
    }

    LaunchedEffect(categories) {
        selectedCategory = categories.first()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Tambah ${if (type == TransactionType.PEMASUKAN) "Pemasukan (Toska)" else "Pengeluaran (Merah)"}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Amount numeric field
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.filter { char -> char.isDigit() } },
                    label = { Text("Jumlah / Nominal (Rp)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("amount_input_field"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF0F172A),
                        unfocusedTextColor = Color(0xFF0F172A),
                        focusedLabelColor = Color(0xFF576F87),
                        unfocusedLabelColor = Color(0xFF576F87),
                        focusedBorderColor = if (type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Short memo input
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan / Deskripsi") },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_input_field"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF0F172A),
                        unfocusedTextColor = Color(0xFF0F172A),
                        focusedLabelColor = Color(0xFF576F87),
                        unfocusedLabelColor = Color(0xFF576F87),
                        focusedBorderColor = if (type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Pilih Kategori:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Simple horizontal wrap-around selectable chips for layout cleanliness
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            categories.take(3).forEach { cat ->
                                CategoryChip(
                                    name = cat,
                                    selected = selectedCategory == cat,
                                    activeColor = if (type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                                    onClick = { selectedCategory = cat }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            categories.drop(3).forEach { cat ->
                                CategoryChip(
                                    name = cat,
                                    selected = selectedCategory == cat,
                                    activeColor = if (type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                                    onClick = { selectedCategory = cat }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val doubleAmount = amountInput.toDoubleOrNull() ?: 0.0
                            if (doubleAmount > 0) {
                                onSave(doubleAmount, noteInput, selectedCategory)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        enabled = amountInput.isNotBlank() && (amountInput.toDoubleOrNull() ?: 0.0) > 0.0,
                        modifier = Modifier.testTag("save_transaction_button")
                    ) {
                        Text("Simpan", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(
    name: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) activeColor else Color(0xFFF1F5F9))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("category_chip_$name")
    ) {
        Text(
            text = name,
            color = if (selected) Color.White else Color(0xFF475569),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// Period Report Breakdown popup detailing filtered records & triggers PDF exports
@Composable
fun ReportOverviewDialog(
    period: String,
    transactions: List<FinancialTransaction>,
    onDismiss: () -> Unit,
    onExportPdf: () -> Unit
) {
    val totalIn = transactions.filter { it.type == TransactionType.PEMASUKAN }.sumOf { it.amount }
    val totalOut = transactions.filter { it.type == TransactionType.PENGELUARAN }.sumOf { it.amount }
    val fmt = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
        maximumFractionDigits = 0
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ringkasan Laporan $period",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Breakdown list
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Pemasukan:", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                text = fmt.format(totalIn).replace("Rp", "Rp "),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ToscaColor
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Pengeluaran:", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                text = fmt.format(totalOut).replace("Rp", "Rp "),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = RedExpenseColor
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sisa Kas (Net):", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                            val net = totalIn - totalOut
                            Text(
                                text = fmt.format(net).replace("Rp", "Rp "),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (net >= 0) ToscaColor else RedExpenseColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Daftar Item (${transactions.size} transaksi)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Mini scrollable list inside dialog
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                ) {
                    if (transactions.isEmpty()) {
                        Text(
                            text = "Tidak ada transaksi dalam periode ini.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(transactions) { t ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(t.note, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("${t.category} • ${SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(t.dateMillis)}", fontSize = 9.sp, color = Color.Gray)
                                    }
                                    Text(
                                        text = "${if (t.type == TransactionType.PEMASUKAN) "+" else "-"} ${fmt.format(t.amount).replace("Rp", "Rp ")}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (t.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onExportPdf,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008080)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("export_pdf_button"),
                    shape = RoundedCornerShape(8.dp),
                    enabled = transactions.isNotEmpty()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bagikan Laporan PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Function to share pdf file through standard Android share sheet (implicit Intent)
fun sharePdf(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Kirim Laporan Keuangan (catat coy)"))
    } catch (e: Exception) {
        Toast.makeText(context, "Gagal membagikan PDF: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}



@Composable
fun EditTransactionDialog(
    transaction: FinancialTransaction,
    onDismiss: () -> Unit,
    onSave: (amount: Double, note: String, category: String) -> Unit
) {
    var amountInput by remember { mutableStateOf(transaction.amount.toLong().toString()) }
    var noteInput by remember { mutableStateOf(transaction.note) }
    var selectedCategory by remember { mutableStateOf(transaction.category) }

    val categories = remember(transaction.type) {
        if (transaction.type == TransactionType.PEMASUKAN) {
            listOf("Gaji", "Sampingan", "Investasi", "Hadiah", "Tabungan", "Lainnya")
        } else {
            listOf("Makanan", "Transportasi", "Belanja", "Kos", "Utilitas", "Lainnya")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Edit ${if (transaction.type == TransactionType.PEMASUKAN) "Pemasukan (Toska)" else "Pengeluaran (Merah)"}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Amount numeric field
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it.filter { char -> char.isDigit() } },
                    label = { Text("Jumlah / Nominal (Rp)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_amount_input_field"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF0F172A),
                        unfocusedTextColor = Color(0xFF0F172A),
                        focusedLabelColor = Color(0xFF576F87),
                        unfocusedLabelColor = Color(0xFF576F87),
                        focusedBorderColor = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Short memo input
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan / Deskripsi") },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_note_input_field"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF0F172A),
                        unfocusedTextColor = Color(0xFF0F172A),
                        focusedLabelColor = Color(0xFF576F87),
                        unfocusedLabelColor = Color(0xFF576F87),
                        focusedBorderColor = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Pilih Kategori:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Simple horizontal wrap-around selectable chips for layout cleanliness
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            categories.take(3).forEach { cat ->
                                CategoryChip(
                                    name = cat,
                                    selected = selectedCategory == cat,
                                    activeColor = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                                    onClick = { selectedCategory = cat }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            categories.drop(3).forEach { cat ->
                                CategoryChip(
                                    name = cat,
                                    selected = selectedCategory == cat,
                                    activeColor = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor,
                                    onClick = { selectedCategory = cat }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val doubleAmount = amountInput.toDoubleOrNull() ?: 0.0
                            if (doubleAmount > 0) {
                                onSave(doubleAmount, noteInput, selectedCategory)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (transaction.type == TransactionType.PEMASUKAN) ToscaColor else RedExpenseColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        enabled = amountInput.isNotBlank() && (amountInput.toDoubleOrNull() ?: 0.0) > 0.0,
                        modifier = Modifier.testTag("save_edit_transaction_button")
                    ) {
                        Text("Perbarui", color = Color.White)
                    }
                }
            }
        }
    }
}


