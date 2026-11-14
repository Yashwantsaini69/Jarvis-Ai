package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DB_NAME = "jarvis_notes.db"
private const val DB_VERSION = 1
private const val TABLE_NOTES = "notes"

object NoteStore {

    data class Note(
        val id: Long,
        val title: String,
        val body: String,
        val remindAt: Long,
        val createdAt: Long,
        val updatedAt: Long
    )

    private val lock = Any()

    @Volatile
    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) {
            synchronized(lock) {
                if (helper == null) helper = Helper(context.applicationContext)
            }
        }
    }

    fun all(): List<Note> {
        val db = writable() ?: return emptyList()
        return synchronized(lock) {
            val notes = mutableListOf<Note>()
            try {
                db.query(
                    TABLE_NOTES,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "updated_at DESC"
                ).use { cursor ->
                    while (cursor.moveToNext()) notes.add(read(cursor))
                }
            } catch (_: Exception) {
            }
            notes
        }
    }

    fun upcoming(): List<Note> {
        val db = writable() ?: return emptyList()
        return synchronized(lock) {
            val notes = mutableListOf<Note>()
            try {
                db.query(
                    TABLE_NOTES,
                    null,
                    "remind_at > ?",
                    arrayOf(System.currentTimeMillis().toString()),
                    null,
                    null,
                    "remind_at ASC"
                ).use { cursor ->
                    while (cursor.moveToNext()) notes.add(read(cursor))
                }
            } catch (_: Exception) {
            }
            notes
        }
    }

    fun get(id: Long): Note? {
        val db = writable() ?: return null
        return synchronized(lock) {
            try {
                db.query(
                    TABLE_NOTES,
                    null,
                    "id = ?",
                    arrayOf(id.toString()),
                    null,
                    null,
                    null,
                    "1"
                ).use { cursor ->
                    if (cursor.moveToFirst()) read(cursor) else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun add(title: String, body: String, remindAt: Long): Long {
        val db = writable() ?: return 0L
        return synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("title", title.trim())
                    put("body", body.trim())
                    put("remind_at", remindAt)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insert(TABLE_NOTES, null, values)
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun update(id: Long, title: String, body: String, remindAt: Long): Boolean {
        val db = writable() ?: return false
        return synchronized(lock) {
            try {
                val values = ContentValues().apply {
                    put("title", title.trim())
                    put("body", body.trim())
                    put("remind_at", remindAt)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE_NOTES, values, "id = ?", arrayOf(id.toString())) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    fun delete(id: Long): Boolean {
        val db = writable() ?: return false
        return synchronized(lock) {
            try {
                db.delete(TABLE_NOTES, "id = ?", arrayOf(id.toString())) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    fun clearReminder(id: Long): Boolean {
        val db = writable() ?: return false
        return synchronized(lock) {
            try {
                val values = ContentValues().apply {
                    put("remind_at", 0L)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE_NOTES, values, "id = ?", arrayOf(id.toString())) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun read(cursor: Cursor): Note = Note(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        title = cursor.getString(cursor.getColumnIndexOrThrow("title")).orEmpty(),
        body = cursor.getString(cursor.getColumnIndexOrThrow("body")).orEmpty(),
        remindAt = cursor.getLong(cursor.getColumnIndexOrThrow("remind_at")),
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
    )

    private fun writable(): SQLiteDatabase? = try {
        helper?.writableDatabase
    } catch (_: Exception) {
        null
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_NOTES (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL DEFAULT ''," +
                    "body TEXT NOT NULL DEFAULT ''," +
                    "remind_at INTEGER NOT NULL DEFAULT 0," +
                    "created_at INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL DEFAULT 0)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
