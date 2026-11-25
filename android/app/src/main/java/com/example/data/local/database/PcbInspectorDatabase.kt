package com.example.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InspectionRecordEntity::class,
        DetectedDefectEntity::class,
        CsvAuditOutboxEntity::class,
        CsvAuditLedgerEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PcbInspectorDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao

    companion object {
        const val DATABASE_NAME = "bharat_pcb_inspector.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add v2 columns to inspection_records
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `patchCoreInferenceMs` INTEGER")
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `target` TEXT NOT NULL DEFAULT 'PCB_BOARD'")
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `transistorRawScore` REAL")
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `transistorDisplayScore` REAL")
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `transistorIsAnomalous` INTEGER")
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `transistorAffectedAreaPercent` REAL")
                db.execSQL("ALTER TABLE `inspection_records` ADD COLUMN `completedEpochMs` INTEGER")

                // 2. Non-destructive table recreation for detected_defects to support nullable coordinates
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `detected_defects_v2` (
                        `defectId` TEXT NOT NULL,
                        `inspectionId` TEXT NOT NULL,
                        `classIndex` INTEGER NOT NULL,
                        `rawClassName` TEXT NOT NULL,
                        `canonicalClassName` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `severity` TEXT NOT NULL,
                        `requiresManualReview` INTEGER NOT NULL,
                        `bboxX` REAL,
                        `bboxY` REAL,
                        `bboxWidth` REAL,
                        `bboxHeight` REAL,
                        `code` TEXT,
                        `description` TEXT,
                        PRIMARY KEY(`defectId`),
                        FOREIGN KEY(`inspectionId`) REFERENCES `inspection_records`(`inspectionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO `detected_defects_v2` (
                        `defectId`, `inspectionId`, `classIndex`, `rawClassName`, `canonicalClassName`,
                        `confidence`, `severity`, `requiresManualReview`, `bboxX`, `bboxY`, `bboxWidth`,
                        `bboxHeight`, `code`, `description`
                    )
                    SELECT
                        `defectId`, `inspectionId`, `classIndex`, `rawClassName`, `canonicalClassName`,
                        `confidence`, `severity`, `requiresManualReview`, `bboxX`, `bboxY`, `bboxWidth`,
                        `bboxHeight`, `code`, `description`
                    FROM `detected_defects`
                """.trimIndent())

                db.execSQL("DROP TABLE `detected_defects`")
                db.execSQL("ALTER TABLE `detected_defects_v2` RENAME TO `detected_defects`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_defects_inspectionId` ON `detected_defects` (`inspectionId`)")
            }
        }

        fun build(context: Context): PcbInspectorDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PcbInspectorDatabase::class.java,
                DATABASE_NAME
            )
            .addMigrations(MIGRATION_1_2)
            .build()
        }
    }
}
