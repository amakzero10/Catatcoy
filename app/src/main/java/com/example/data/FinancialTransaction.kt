package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class TransactionType {
    PEMASUKAN, PENGELUARAN
}

@Entity(tableName = "transactions")
data class FinancialTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val note: String,
    val category: String,
    val dateMillis: Long, // timestamp
    val type: TransactionType
)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY dateMillis DESC")
    fun getAllTransactions(): Flow<List<FinancialTransaction>>

    @Query("SELECT * FROM transactions WHERE dateMillis >= :cutoffDate ORDER BY dateMillis DESC")
    fun getTransactionsSince(cutoffDate: Long): Flow<List<FinancialTransaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: FinancialTransaction)

    @Delete
    suspend fun deleteTransaction(transaction: FinancialTransaction)
}

@Database(entities = [FinancialTransaction::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile
        private var INSTANCE: FinanceDatabase? = null

        fun getDatabase(context: Context): FinanceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "finance_database_v2"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String {
        return value.name
    }

    @TypeConverter
    fun toTransactionType(value: String): TransactionType {
        return TransactionType.valueOf(value)
    }
}

class TransactionRepository(private val transactionDao: TransactionDao) {
    val allTransactions: Flow<List<FinancialTransaction>> = transactionDao.getAllTransactions()

    fun getTransactionsSince(cutoffDate: Long): Flow<List<FinancialTransaction>> =
        transactionDao.getTransactionsSince(cutoffDate)

    suspend fun insert(transaction: FinancialTransaction) = transactionDao.insertTransaction(transaction)

    suspend fun delete(transaction: FinancialTransaction) = transactionDao.deleteTransaction(transaction)
}
