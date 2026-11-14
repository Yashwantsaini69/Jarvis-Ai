package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

private const val DB_NAME = "jarvis_memory.db"
private const val DB_VERSION = 4
private const val TABLE_MEMORIES = "memories"
private const val TABLE_META = "meta"
private const val MAX_ROWS = 300
private const val META_SIGNATURE = "last_processed_signature"
private const val META_DOCUMENT = "memory_document"
private const val MAX_DOC_CHARS = 8000
private const val SOURCE_VOICE = "voice"
private const val SOURCE_MANUAL = "manual"

private const val RANK_ORDER = "importance DESC, updated_at DESC"

private val ALLOWED_TYPES = setOf(
    "identity", "location", "work", "education", "preference", "project", "goal", "note"
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

    val categories = listOf("Personal", "Important", "Others")

    data class MemoryItem(
        val id: Long,
        val type: String,
        val key: String,
        val value: String,
        val importance: Int,
        val updatedAt: Long,
        val title: String = "",
        val category: String = ""
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

    fun allowedType(type: String): Boolean = type in ALLOWED_TYPES

    fun document(): String {
        val db = writable() ?: return ""
        return synchronized(lock) {
            try {
                readMeta(db, META_DOCUMENT) ?: buildDocumentFromRows(db).also {
                    writeMeta(db, META_DOCUMENT, it)
                }
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun saveDocument(text: String): Boolean {
        val db = writable() ?: return false
        return synchronized(lock) {
            try {
                writeMeta(db, META_DOCUMENT, cleanDocument(text))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun clearDocument(): Int {
        val db = writable() ?: return 0
        return synchronized(lock) {
            try {
                val before = readMeta(db, META_DOCUMENT) ?: buildDocumentFromRows(db)
                writeMeta(db, META_DOCUMENT, "")
                before.length
            } catch (_: Exception) {
                0
            }
        }
    }

    fun saveEntries(entries: List<MemoryExtractor.MemoryEntry>): Int {
        if (entries.isEmpty()) return 0
        val db = writable() ?: return 0
        return synchronized(lock) {
            try {
                var doc = currentDocument(db)
                var added = 0
                for (entry in entries) {
                    val merged = mergeLine(doc, documentLine(entry.type, entry.key, entry.value))
                    if (merged != doc) {
                        doc = merged
                        added++
                    }
                }
                if (added > 0) writeMeta(db, META_DOCUMENT, doc.take(MAX_DOC_CHARS))
                added
            } catch (_: Exception) {
                0
            }
        }
    }

    fun observeUserMessage(messageId: Long, text: String, final: Boolean): Int {
        if (!final) return 0
        val clean = text.trim()
        if (clean.length < 3) return 0
        MemoryBrain.observe(messageId, clean)
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
                try {
                    var doc = currentDocument(db)
                    var applied = 0
                    for (command in commands) {
                        when (command) {
                            is MemoryExtractor.Command.Save -> {
                                val line = documentLine(
                                    command.entry.type,
                                    command.entry.key,
                                    command.entry.value
                                )
                                val merged = mergeLine(doc, line)
                                if (merged != doc) {
                                    doc = merged
                                    applied++
                                }
                            }
                            is MemoryExtractor.Command.Forget -> {
                                val result = forgetFromDocument(doc, command.query)
                                if (result.first != doc) {
                                    doc = result.first
                                    applied += result.second
                                }
                            }
                            MemoryExtractor.Command.Clear -> {
                                if (doc.isNotEmpty()) {
                                    doc = ""
                                    applied++
                                }
                            }
                        }
                    }
                    if (applied > 0) {
                        writeMeta(db, META_DOCUMENT, doc.take(MAX_DOC_CHARS))
                        writeMeta(db, META_SIGNATURE, signature)
                    }
                    applied
                } catch (_: Exception) {
                    0
                }
            }
        }
    }

    fun promptBlock(maxChars: Int = 1600): String {
        val doc = document().trim()
        if (doc.isEmpty()) return ""
        val header = "PERSISTENT MEMORY about the user from earlier conversations. " +
            "Use it naturally and never recite or list it. " +
            "If the user corrects a detail, follow the newest value.\n"
        val budget = (maxChars - header.length).coerceAtLeast(200)
        val body = if (doc.length > budget) doc.take(budget).trimEnd() else doc
        return header + body
    }

    fun snapshot(): List<MemoryItem> {
        val db = readable() ?: return emptyList()
        return synchronized(lock) {
            val out = mutableListOf<MemoryItem>()
            val cursor = try {
                db.query(
                    TABLE_MEMORIES,
                    arrayOf("id", "type", "key", "value", "importance", "updated_at", "title", "category"),
                    null, null, null, null, RANK_ORDER
                )
            } catch (_: Exception) {
                return@synchronized out
            }
            cursor.use {
                while (it.moveToNext()) {
                    out.add(readItem(it))
                }
            }
            out
        }
    }

    private fun currentDocument(db: SQLiteDatabase): String =
        readMeta(db, META_DOCUMENT) ?: buildDocumentFromRows(db)

    private fun buildDocumentFromRows(db: SQLiteDatabase): String {
        var doc = ""
        val cursor = try {
            db.query(
                TABLE_MEMORIES,
                arrayOf("id", "type", "key", "value", "importance", "updated_at", "title", "category"),
                null, null, null, null, RANK_ORDER
            )
        } catch (_: Exception) {
            return ""
        }
        cursor.use {
            while (it.moveToNext()) {
                doc = mergeLine(doc, lineForItem(readItem(it)))
            }
        }
        return doc
    }

    private fun lineForItem(item: MemoryItem): String {
        val value = item.value.trim()
        if (value.isEmpty()) return ""
        if (item.key.startsWith("manual:")) {
            val title = item.title.trim()
            if (title.isEmpty()) return value
            if (normalize(title) == normalize(value)) return title
            if (containsWords(normalize(title), normalize(value))) return title
            return "$title: $value"
        }
        return documentLine(item.type, item.key, value)
    }

    private fun documentLine(type: String, key: String, rawValue: String): String {
        val value = rawValue.trim().replace(Regex("\\s+"), " ")
        if (value.isEmpty()) return ""
        val label = when (type) {
            "identity" -> when (key) {
                "name" -> "Name"
                "birthday" -> "Birthday"
                else -> ""
            }
            "location" -> "City"
            "work" -> "Work"
            "education" -> "Education"
            "preference" -> when {
                key.startsWith("like:") -> "Likes"
                key.startsWith("dislike:") -> "Dislikes"
                else -> "Preference"
            }
            "project" -> "Project"
            "goal" -> "Goal"
            else -> ""
        }
        return if (label.isEmpty()) value else "$label: $value"
    }

    private fun mergeLine(doc: String, line: String): String {
        val newLine = line.trim()
        if (newLine.isEmpty()) return doc
        if (MemoryExtractor.isSensitiveValue(newLine)) return doc
        val newCore = lineCore(newLine)
        if (newCore.isEmpty()) return doc
        val lines = doc.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var index = 0
        while (index < lines.size) {
            val oldCore = lineCore(lines[index])
            when {
                oldCore == newCore -> return doc
                containsWords(oldCore, newCore) -> return doc
                containsWords(newCore, oldCore) -> {
                    lines[index] = newLine
                    return lines.joinToString("\n")
                }
            }
            index++
        }
        lines.add(newLine)
        return lines.joinToString("\n")
    }

    private fun lineCore(line: String): String {
        val body = line.trim().removePrefix("-").trim()
        val separator = body.indexOf(": ")
        val value = if (separator >= 0) body.substring(separator + 2) else body
        return normalize(value)
    }

    private fun containsWords(haystack: String, needle: String): Boolean {
        if (haystack.isBlank() || needle.isBlank()) return false
        return " $haystack ".contains(" $needle ")
    }

    private fun forgetFromDocument(doc: String, query: String): Pair<String, Int> {
        val tokens = normalize(query).split(' ').filter { it.length >= 2 && it !in STOPWORDS }
        if (tokens.isEmpty()) return doc to 0
        val kept = mutableListOf<String>()
        var removed = 0
        for (line in doc.lines()) {
            if (line.isBlank()) continue
            val normalized = normalize(line)
            if (tokens.any { " $normalized ".contains(" $it ") }) {
                removed++
            } else {
                kept.add(line.trim())
            }
        }
        return kept.joinToString("\n") to removed
    }

    private fun cleanDocument(text: String): String =
        text.lines().map { it.trimEnd() }.joinToString("\n").trim().take(MAX_DOC_CHARS)

    fun addManual(title: String, content: String, category: String): Long {
        val db = writable() ?: return -1L
        val cleanTitle = title.trim().take(120)
        val cleanContent = content.trim().take(2000)
        if (cleanTitle.isBlank() || cleanContent.isBlank()) return -1L
        val safeCategory = normalizeCategory(category)
        val now = System.currentTimeMillis()
        val key = "manual:" + UUID.randomUUID().toString().take(12)
        return synchronized(lock) {
            try {
                val values = ContentValues().apply {
                    put("type", typeForCategory(safeCategory))
                    put("key", key)
                    put("value", cleanContent)
                    put("importance", importanceForCategory(safeCategory))
                    put("confidence", 1.0)
                    put("source", SOURCE_MANUAL)
                    put("created_at", now)
                    put("updated_at", now)
                    put("last_used_at", 0L)
                    put("title", cleanTitle)
                    put("category", safeCategory)
                }
                val id = db.insert(TABLE_MEMORIES, null, values)
                if (id > 0) prune(db)
                id
            } catch (_: Exception) {
                -1L
            }
        }
    }

    fun updateMemory(id: Long, title: String, content: String, category: String): Boolean {
        val db = writable() ?: return false
        val cleanTitle = title.trim().take(120)
        val cleanContent = content.trim().take(2000)
        if (cleanTitle.isBlank() || cleanContent.isBlank()) return false
        val safeCategory = normalizeCategory(category)
        return synchronized(lock) {
            try {
                val values = ContentValues().apply {
                    put("title", cleanTitle)
                    put("value", cleanContent)
                    put("category", safeCategory)
                    put("importance", importanceForCategory(safeCategory))
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE_MEMORIES, values, "id = ?", arrayOf(id.toString())) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    fun deleteMemory(id: Long): Boolean {
        val db = writable() ?: return false
        return synchronized(lock) {
            try {
                db.delete(TABLE_MEMORIES, "id = ?", arrayOf(id.toString())) > 0
            } catch (_: Exception) {
                false
            }
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
        val category = categoryForEntry(entry.type, importance)
        val title = titleFor(entry.type, key)
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
                    put("category", category)
                    put("title", title)
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
                    put("category", category)
                    put("title", title)
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

    private fun readItem(cursor: android.database.Cursor): MemoryItem = MemoryItem(
        cursor.getLong(0),
        cursor.getString(1),
        cursor.getString(2),
        cursor.getString(3),
        cursor.getInt(4),
        cursor.getLong(5),
        cursor.getString(6) ?: "",
        cursor.getString(7) ?: ""
    )

    fun displayTitle(item: MemoryItem): String {
        val stored = item.title.trim()
        if (stored.isNotEmpty()) return stored
        return titleFor(item.type, item.key)
    }

    private fun titleFor(type: String, key: String): String = when (type) {
        "identity" -> when (key) {
            "name" -> "Name"
            "birthday" -> "Birthday"
            else -> "Identity"
        }
        "location" -> if (key == "city") "City" else "Location"
        "work" -> "Work"
        "education" -> "Education"
        "preference" -> when {
            key.startsWith("like:") -> "Like: " + humanizeSlot(key.removePrefix("like:"))
            key.startsWith("dislike:") -> "Dislike: " + humanizeSlot(key.removePrefix("dislike:"))
            else -> "Preference"
        }
        "project" -> "Project: " + humanizeSlot(key.removePrefix("project:"))
        "goal" -> "Goal: " + humanizeSlot(key.removePrefix("goal:"))
        "note" -> {
            val slot = humanizeSlot(key.removePrefix("note:"))
            if (slot.isBlank() || slot.equals("Entry", ignoreCase = true)) "Note" else "Note: $slot"
        }
        else -> {
            val slot = humanizeSlot(key)
            if (slot.isNotBlank()) {
                slot
            } else {
                type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }

    private fun humanizeSlot(raw: String): String = raw
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        .take(40)

    fun displayCategory(item: MemoryItem): String {
        val stored = item.category.trim()
        if (stored.isNotEmpty()) {
            categories.firstOrNull { it.equals(stored, ignoreCase = true) }?.let { return it }
            when (stored.lowercase()) {
                "important notes", "important note", "important" -> return "Important"
                "preferences", "work", "study", "personal" -> return "Personal"
                "other", "others" -> return "Others"
            }
        }
        return categoryForEntry(item.type, item.importance)
    }

    private fun categoryForEntry(type: String, importance: Int): String = when (type) {
        "identity", "project", "goal" -> "Important"
        "preference" -> if (importance >= 4) "Important" else "Personal"
        "location", "work", "education" -> "Personal"
        "note" -> if (importance >= 4) "Important" else "Others"
        else -> "Others"
    }

    private fun normalizeCategory(category: String): String {
        val chosen = category.trim()
        return categories.firstOrNull { it.equals(chosen, ignoreCase = true) }
            ?: when (chosen.lowercase()) {
                "important notes", "important note", "important" -> "Important"
                "preferences", "work", "study", "personal" -> "Personal"
                "other", "others" -> "Others"
                else -> "Others"
            }
    }

    private fun typeForCategory(category: String): String = when (category) {
        "Personal" -> "identity"
        "Important", "Others" -> "note"
        else -> "note"
    }

    private fun importanceForCategory(category: String): Int = when (category) {
        "Important" -> 5
        "Personal" -> 4
        else -> 3
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
                    "title TEXT NOT NULL DEFAULT ''," +
                    "category TEXT NOT NULL DEFAULT ''," +
                    "UNIQUE(type, key))"
            )
            db.execSQL("CREATE TABLE $TABLE_META (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "CREATE INDEX idx_memories_rank ON $TABLE_MEMORIES (importance DESC, updated_at DESC)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE_MEMORIES ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE_MEMORIES ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 3) {
                db.execSQL("UPDATE $TABLE_MEMORIES SET category = 'Personal' WHERE category IN ('Preferences', 'Work', 'Study')")
                db.execSQL("UPDATE $TABLE_MEMORIES SET category = 'Important' WHERE category = 'Important Notes'")
            }
            if (oldVersion < 4) {
                db.execSQL("UPDATE $TABLE_MEMORIES SET category = 'Others' WHERE lower(category) = 'other'")
                db.execSQL(
                    "UPDATE $TABLE_MEMORIES SET category = CASE " +
                        "WHEN type IN ('identity', 'project', 'goal') THEN 'Important' " +
                        "WHEN type IN ('location', 'work', 'education') THEN 'Personal' " +
                        "WHEN type = 'preference' AND importance >= 4 THEN 'Important' " +
                        "WHEN type = 'preference' THEN 'Personal' " +
                        "WHEN type = 'note' AND importance >= 4 THEN 'Important' " +
                        "ELSE 'Others' END " +
                        "WHERE category = '' OR category IS NULL"
                )
            }
        }
    }
}
