package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val DB_NAME = "jarvis_memory.db"
private const val DB_VERSION = 1
private const val TABLE_MEMORIES = "memories"
private const val TABLE_META = "meta"
private const val MAX_ROWS = 300
private const val META_SIGNATURE = "last_processed_signature"
private const val SOURCE_VOICE = "voice"

private const val RANK_ORDER = "importance DESC, updated_at DESC"

private val ALLOWED_TYPES = setOf(
    "identity", "location", "work", "education", "preference", "project", "note"
)

private val STOPWORDS = setOf(
    "my", "mera", "meri", "mujhe", "muje", "mujhko", "about", "regarding",
    "the", "that", "ki", "ka", "ke", "ko", "se", "memory", "memories",
    "yaad", "please", "delete", "remove", "erase", "forget", "bhool", "bhul",
    "jao", "do", "all", "everything", "detail", "details", "info",
    "information", "baare", "bare", "hatao", "mita", "clear", "sab", "kuch",
    "aur", "this", "is", "it", "was", "were", "hai", "h"
)

private val TARGET_KEYWORDS: Map<String, Pair<String, String?>> = mapOf(
    "name" to ("identity" to "name"),
    "naam" to ("identity" to "name"),
    "city" to ("location" to "city"),
    "shehar" to ("location" to "city"),
    "shahar" to ("location" to "city"),
    "gaon" to ("location" to "city"),
    "location" to ("location" to "city"),
    "address" to ("location" to "city"),
    "birthday" to ("identity" to "birthday"),
    "birthdate" to ("identity" to "birthday"),
    "janmadin" to ("identity" to "birthday"),
    "dob" to ("identity" to "birthday"),
    "work" to ("work" to "employer"),
    "job" to ("work" to "employer"),
    "employer" to ("work" to "employer"),
    "company" to ("work" to "employer"),
    "office" to ("work" to "employer"),
    "kaam" to ("work" to "employer"),
    "naukri" to ("work" to "employer"),
    "study" to ("education" to "school"),
    "studies" to ("education" to "school"),
    "school" to ("education" to "school"),
    "college" to ("education" to "school"),
    "education" to ("education" to "school"),
    "padhai" to ("education" to "school"),
    "like" to ("preference" to "like:"),
    "likes" to ("preference" to "like:"),
    "pasand" to ("preference" to "like:"),
    "favourite" to ("preference" to "like:"),
    "favorite" to ("preference" to "like:"),
    "love" to ("preference" to "like:"),
    "dislike" to ("preference" to "dislike:"),
    "dislikes" to ("preference" to "dislike:"),
    "napasand" to ("preference" to "dislike:"),
    "hate" to ("preference" to "dislike:"),
    "nafrat" to ("preference" to "dislike:"),
    "project" to ("project" to null),
    "projects" to ("project" to null)
)

object MemoryStore {

    data class MemoryItem(
        val id: Long,
        val type: String,
        val key: String,
        val value: String,
        val importance: Int,
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

    fun observeUserMessage(messageId: Long, text: String, final: Boolean): Int {
        if (!final) return 0
        val clean = text.trim()
        if (clean.length < 3) return 0
        val commands = try {
            MemoryExtractor.extract(clean)
        } catch (_: Exception) {
            return 0
        }
        if (commands.isEmpty()) return 0
        val db = writable() ?: return 0
        return synchronized(lock) {
            val signature = "$messageId:${clean.hashCode()}"
            if (readMeta(db, META_SIGNATURE) == signature) {
                0
            } else {
                var applied = 0
                try {
                    db.beginTransaction()
                    for (command in commands) applied += applyCommand(db, command)
                    writeMeta(db, META_SIGNATURE, signature)
                    prune(db)
                    db.setTransactionSuccessful()
                } catch (_: Exception) {
                    applied = 0
                } finally {
                    try {
                        db.endTransaction()
                    } catch (_: Exception) {
                    }
                }
                applied
            }
        }
    }

    fun promptBlock(maxChars: Int = 1100): String {
        val db = readable() ?: return ""
        val items = synchronized(lock) {
            val out = mutableListOf<MemoryItem>()
            val cursor = try {
                db.query(
                    TABLE_MEMORIES,
                    arrayOf("id", "type", "key", "value", "importance", "updated_at"),
                    null, null, null, null, RANK_ORDER, "60"
                )
            } catch (_: Exception) {
                return@synchronized out
            }
            cursor.use {
                while (it.moveToNext()) {
                    out.add(
                        MemoryItem(
                            it.getLong(0),
                            it.getString(1),
                            it.getString(2),
                            it.getString(3),
                            it.getInt(4),
                            it.getLong(5)
                        )
                    )
                }
            }
            out
        }
        if (items.isEmpty()) return ""

        val grouped = LinkedHashMap<String, MutableList<MemoryItem>>()
        for (item in items) {
            grouped.getOrPut(labelFor(item)) { mutableListOf() }.add(item)
        }

        val header = "PERSISTENT MEMORY about the user from earlier conversations. " +
            "Use it naturally and never recite or list it. " +
            "If the user corrects a detail, follow the newest value.\n"
        val body = StringBuilder()
        val used = mutableListOf<Long>()

        for ((label, values) in grouped) {
            val full = values.joinToString(", ") { it.value }
            val shown = if (full.length > 220) full.take(217).trimEnd() + "..." else full
            val line = "- $label: $shown"
            if (header.length + body.length + line.length + 1 > maxChars) break
            body.append(line).append('\n')
            if (shown == full) values.forEach { used.add(it.id) }
        }
        if (body.length == 0) return ""

        if (used.isNotEmpty()) {
            val writableDb = writable()
            if (writableDb != null) {
                synchronized(lock) {
                    try {
                        touch(writableDb, used)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return header + body.toString().trimEnd()
    }

    fun snapshot(): List<MemoryItem> {
        val db = readable() ?: return emptyList()
        return synchronized(lock) {
            val out = mutableListOf<MemoryItem>()
            val cursor = try {
                db.query(
                    TABLE_MEMORIES,
                    arrayOf("id", "type", "key", "value", "importance", "updated_at"),
                    null, null, null, null, RANK_ORDER
                )
            } catch (_: Exception) {
                return@synchronized out
            }
            cursor.use {
                while (it.moveToNext()) {
                    out.add(
                        MemoryItem(
                            it.getLong(0),
                            it.getString(1),
                            it.getString(2),
                            it.getString(3),
                            it.getInt(4),
                            it.getLong(5)
                        )
                    )
                }
            }
            out
        }
    }

    fun clear(): Int {
        val db = writable() ?: return 0
        return synchronized(lock) {
            try {
                clearInternal(db)
            } catch (_: Exception) {
                0
            }
        }
    }

    private fun writable(): SQLiteDatabase? = try {
        helper?.writableDatabase
    } catch (_: Exception) {
        null
    }

    private fun readable(): SQLiteDatabase? = try {
        helper?.readableDatabase
    } catch (_: Exception) {
        null
    }

    private fun applyCommand(db: SQLiteDatabase, command: MemoryExtractor.Command): Int =
        when (command) {
            is MemoryExtractor.Command.Save -> saveEntry(db, command.entry)
            is MemoryExtractor.Command.Forget -> forgetInternal(db, command.query)
            MemoryExtractor.Command.Clear -> clearInternal(db)
        }

    private fun saveEntry(db: SQLiteDatabase, entry: MemoryExtractor.MemoryEntry): Int {
        if (entry.type !in ALLOWED_TYPES) return 0
        val value = entry.value.trim().take(200)
        if (value.length < 2) return 0
        if (MemoryExtractor.isSensitiveValue(value)) return 0
        val key = entry.key.trim().lowercase().take(160)
        if (key.isBlank()) return 0

        if (entry.type == "preference") {
            when {
                key.startsWith("like:") -> db.delete(
                    TABLE_MEMORIES,
                    "type = ? AND key = ?",
                    arrayOf("preference", "dislike:" + key.removePrefix("like:"))
                )
                key.startsWith("dislike:") -> db.delete(
                    TABLE_MEMORIES,
                    "type = ? AND key = ?",
                    arrayOf("preference", "like:" + key.removePrefix("dislike:"))
                )
            }
        }

        val now = System.currentTimeMillis()
        val importance = entry.importance.coerceIn(1, 5)
        val confidence = entry.confidence.coerceIn(0.0, 1.0)
        var wrote = 0
        val cursor = db.query(
            TABLE_MEMORIES,
            arrayOf("id", "importance"),
            "type = ? AND key = ?",
            arrayOf(entry.type, key),
            null, null, null, "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val oldImportance = it.getInt(1)
                val values = ContentValues().apply {
                    put("value", value)
                    put("importance", maxOf(oldImportance, importance))
                    put("confidence", confidence)
                    put("updated_at", now)
                }
                wrote = db.update(TABLE_MEMORIES, values, "id = ?", arrayOf(id.toString()))
            } else {
                val values = ContentValues().apply {
                    put("type", entry.type)
                    put("key", key)
                    put("value", value)
                    put("importance", importance)
                    put("confidence", confidence)
                    put("source", SOURCE_VOICE)
                    put("created_at", now)
                    put("updated_at", now)
                    put("last_used_at", 0L)
                }
                wrote = if (db.insert(TABLE_MEMORIES, null, values) > 0) 1 else 0
            }
        }
        return if (wrote > 0) 1 else 0
    }

    private fun forgetInternal(db: SQLiteDatabase, query: String): Int {
        val normalized = normalize(query)
        if (normalized.isBlank()) return 0
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val targets = resolveTargets(tokens)
        val content = tokens
            .filter { it.length >= 2 && it !in STOPWORDS && it !in TARGET_KEYWORDS }
            .distinct()
        if (targets.isEmpty() && content.isEmpty()) return 0

        var deleted = 0
        if (targets.isEmpty()) {
            for (token in content) {
                deleted += db.delete(
                    TABLE_MEMORIES,
                    "lower(value) LIKE ? OR lower(key) LIKE ?",
                    arrayOf("%$token%", "%$token%")
                )
            }
            return deleted
        }

        for ((type, prefix) in targets) {
            val clauses = mutableListOf("type = ?")
            val args = mutableListOf(type)
            if (prefix != null) {
                if (prefix.endsWith(":")) {
                    clauses.add("key LIKE ?")
                    args.add("$prefix%")
                } else {
                    clauses.add("key = ?")
                    args.add(prefix)
                }
            }
            if (content.isNotEmpty()) {
                val inner = mutableListOf<String>()
                for (token in content) {
                    inner.add("(lower(value) LIKE ? OR lower(key) LIKE ?)")
                    args.add("%$token%")
                    args.add("%$token%")
                }
                clauses.add("(" + inner.joinToString(" OR ") + ")")
            }
            deleted += db.delete(
                TABLE_MEMORIES,
                clauses.joinToString(" AND "),
                args.toTypedArray()
            )
        }
        return deleted
    }

    private fun clearInternal(db: SQLiteDatabase): Int {
        val deleted = db.delete(TABLE_MEMORIES, null, null)
        writeMeta(db, META_SIGNATURE, "")
        return deleted
    }

    private fun normalize(query: String): String =
        query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun resolveTargets(tokens: List<String>): List<Pair<String, String?>> {
        val found = LinkedHashMap<String, Pair<String, String?>>()
        for (token in tokens) {
            val target = TARGET_KEYWORDS[token] ?: continue
            found[target.first + "|" + (target.second ?: "*")] = target
        }
        return found.values.toList()
    }

    private fun labelFor(item: MemoryItem): String = when (item.type) {
        "identity" -> when (item.key) {
            "name" -> "Name"
            "birthday" -> "Birthday"
            else -> "Identity"
        }
        "location" -> if (item.key == "city") "City" else "Location"
        "work" -> "Work"
        "education" -> "Education"
        "preference" -> when {
            item.key.startsWith("like:") -> "Likes"
            item.key.startsWith("dislike:") -> "Dislikes"
            else -> "Preferences"
        }
        "project" -> "Projects"
        "note" -> "Notes"
        else -> item.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun touch(db: SQLiteDatabase, ids: List<Long>) {
        val unique = ids.distinct()
        if (unique.isEmpty()) return
        val placeholders = unique.joinToString(",") { "?" }
        val args = arrayOfNulls<Any>(unique.size + 1)
        args[0] = System.currentTimeMillis()
        for (i in unique.indices) args[i + 1] = unique[i]
        db.execSQL(
            "UPDATE $TABLE_MEMORIES SET last_used_at = ? WHERE id IN ($placeholders)",
            args
        )
    }

    private fun prune(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE_MEMORIES WHERE id NOT IN " +
                "(SELECT id FROM $TABLE_MEMORIES ORDER BY $RANK_ORDER LIMIT $MAX_ROWS)"
        )
    }

    private fun readMeta(db: SQLiteDatabase, key: String): String? {
        val cursor = try {
            db.query(TABLE_META, arrayOf("value"), "key = ?", arrayOf(key), null, null, null, "1")
        } catch (_: Exception) {
            return null
        }
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun writeMeta(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict(TABLE_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_MEMORIES (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "type TEXT NOT NULL," +
                    "key TEXT NOT NULL," +
                    "value TEXT NOT NULL," +
                    "importance INTEGER NOT NULL DEFAULT 3," +
                    "confidence REAL NOT NULL DEFAULT 0.9," +
                    "source TEXT NOT NULL DEFAULT 'voice'," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "last_used_at INTEGER NOT NULL DEFAULT 0," +
                    "UNIQUE(type, key))"
            )
            db.execSQL("CREATE TABLE $TABLE_META (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "CREATE INDEX idx_memories_rank ON $TABLE_MEMORIES (importance DESC, updated_at DESC)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORIES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_META")
            onCreate(db)
        }
    }
}
