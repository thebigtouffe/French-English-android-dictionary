package com.thebigtouffe.french_english

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.zip.GZIPInputStream

/**
 * Simple database helper with prefix search using LIKE
 * No FTS needed - just uses standard SQL LIKE
 */
class DictionaryDatabaseHelper(context: Context) : 
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "dictionary.db"
        private const val DATABASE_VERSION = 1
        
        const val TABLE_ENTRIES = "entries"
        const val COLUMN_ID = "id"
        const val COLUMN_WORD = "word"
        const val COLUMN_WORD_LOWER = "word_lower"
        const val COLUMN_ENTRY_COMPRESSED = "entry_compressed"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Database is created by Python script
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Not needed for pre-built database
    }

    /**
     * Decompress a GZIP byte array to string
     */
    private fun decompressBytes(compressed: ByteArray): String {
        return GZIPInputStream(compressed.inputStream()).use { gzipStream ->
            gzipStream.readBytes().toString(Charsets.UTF_8)
        }
    }

    /**
     * Search for words by prefix (e.g., "hel" finds "hello", "help", "helpful")
     * Returns list of matching words
     */
    fun searchByPrefix(prefix: String, limit: Int = 20): List<String> {
        val results = mutableListOf<String>()
        val db = readableDatabase
        
        val cursor = db.query(
            TABLE_ENTRIES,
            arrayOf(COLUMN_WORD),
            "$COLUMN_WORD_LOWER LIKE ?",
            arrayOf("${prefix.lowercase()}%"),
            null,
            null,
            COLUMN_WORD_LOWER,
            limit.toString()
        )
        
        cursor.use {
            while (it.moveToNext()) {
                results.add(it.getString(0))
            }
        }
        
        return results
    }

    /**
     * Get the full entry for a word
     * Returns decompressed XML entry
     */
    fun getEntry(word: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_ENTRIES,
            arrayOf(COLUMN_ENTRY_COMPRESSED),
            "$COLUMN_WORD_LOWER = ?",
            arrayOf(word.lowercase()),
            null,
            null,
            null,
            "1"
        )
        
        return cursor.use {
            if (it.moveToFirst()) {
                val compressedBytes = it.getBlob(0)
                decompressBytes(compressedBytes)
            } else {
                null
            }
        }
    }

    /**
     * Get the count of entries in the database
     */
    fun getEntryCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ENTRIES", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Get database size statistics
     */
    fun getDatabaseStats(): DatabaseStats {
        val db = readableDatabase
        val dbFile = db.path?.let { java.io.File(it) }
        val dbSizeBytes = dbFile?.length() ?: 0L
        
        return DatabaseStats(
            entryCount = getEntryCount(),
            databaseSizeBytes = dbSizeBytes
        )
    }

    data class DatabaseStats(
        val entryCount: Int,
        val databaseSizeBytes: Long
    ) {
        fun getDatabaseSizeMB(): Double = databaseSizeBytes / (1024.0 * 1024.0)
    }
}
