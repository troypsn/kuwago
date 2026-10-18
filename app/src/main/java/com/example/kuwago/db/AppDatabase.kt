package com.example.kuwago.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        SmsMessageEntity::class,
        AnalysisResultEntity::class,
        UrlAnalysisEntity::class,
        FinalDecisionEntity::class,
        NotificationEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun homeStatsDao(): HomeStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_result ADD COLUMN overall_explanation TEXT")
                db.execSQL("ALTER TABLE analysis_result ADD COLUMN explanation TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val passphrase = DatabaseSecurityManager.getOrGeneratePassphrase(context)
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "kuwago_encrypted.db"
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}
