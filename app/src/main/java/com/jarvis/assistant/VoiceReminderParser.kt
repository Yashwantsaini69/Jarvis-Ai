package com.jarvis.assistant

import java.util.Calendar

object VoiceReminderParser {

    data class Parsed(
        val title: String,
        val body: String,
        val atMillis: Long
    )

    private val triggerWords = setOf(
        "reminder", "reminders", "remind", "alarm", "alert",
        "रिमाइंडर", "अलार्म", "एलर्ट"
    )

    private val clockWords = setOf("baje", "baj", "oclock", "o'clock", "बजे")

    private val meridianWords = setOf("am", "pm")

    private val afterWords = setOf("baad", "bad", "after", "बाद")

    private val dateTomorrowWords = setOf("kal", "tomorrow", "कल")
    private val dateTodayWords = setOf("aaj", "today", "आज")
    private val dateParsoWords = setOf("parso", "parson", "परसों")

    private val morningWords = setOf("subah", "morning", "सुबह")
    private val afternoonWords = setOf("dopahar", "afternoon", "दोपहर")
    private val eveningWords = setOf("shaam", "sham", "evening", "शाम")
    private val nightWords = setOf("raat", "rat", "night", "रात")

    private val unitSeconds: Map<String, Long> = mapOf(
        "second" to 1L, "seconds" to 1L, "sec" to 1L, "secs" to 1L, "सेकंड" to 1L,
        "minute" to 60L, "minutes" to 60L, "min" to 60L, "mins" to 60L, "मिनट" to 60L,
        "hour" to 3600L, "hours" to 3600L, "ghanta" to 3600L, "ghante" to 3600L,
        "घंटा" to 3600L, "घंटे" to 3600L
    )

    private val fillerWords = setOf(
        "please", "pls", "plz", "me", "to", "at", "on", "for", "set", "lagao",
        "lagana", "lagado", "laga", "lag", "kar", "karo", "kardo", "do", "de",
        "dijiye", "mujhe", "muje", "mera", "meri", "ka", "ke", "ki", "liye", "liy",
        "pe", "par", "ko", "se", "tak", "time", "dena", "deni", "dunga", "dungi",
        "denge", "dilana", "yaad", "dila", "dilao", "dilado",
        "remind", "मुझे", "मेरा", "मेरी", "का", "के", "की", "लिए", "याद", "दिला",
        "दिलाओ", "लगाओ", "करो", "कर", "दो", "सेट", "पे", "पर", "को", "से", "तक",
        "समय", "देना", "दूंगा", "दूंगी"
    )

    fun hasTrigger(raw: String): Boolean = isReminderRequest(tokenize(raw))

    fun parse(raw: String, now: Long = System.currentTimeMillis()): Parsed? {
        val tokens = tokenize(raw)
        if (!isReminderRequest(tokens)) return null

        val used = mutableSetOf<Int>()
        markTrigger(tokens, used)

        val relativeMillis = parseRelative(tokens, used, now)
        val clockMillis = if (relativeMillis == null) parseClock(tokens, used, now) else null
        val at = relativeMillis ?: clockMillis ?: return null

        val content = tokens.filterIndexed { index, token ->
            index !in used &&
                token !in triggerWords &&
                token !in clockWords &&
                token !in meridianWords &&
                token !in afterWords &&
                token !in dateTomorrowWords &&
                token !in dateTodayWords &&
                token !in dateParsoWords &&
                token !in morningWords &&
                token !in afternoonWords &&
                token !in eveningWords &&
                token !in nightWords &&
                token !in fillerWords &&
                token !in unitSeconds.keys
        }
        val title = content.joinToString(" ").trim().ifEmpty { "Reminder" }
        val pretty = title.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        return Parsed(pretty, pretty, at)
    }

    private fun tokenize(raw: String): List<String> {
        return raw.lowercase()
            .replace(Regex("(\\d)(am|pm)\\b"), "$1 $2")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
    }

    private fun isReminderRequest(tokens: List<String>): Boolean {
        if (tokens.any { it in triggerWords || it.startsWith("remind") }) return true
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == "yaad" && tokens[i + 1].startsWith("dila")) return true
            if (tokens[i] == "याद" && tokens[i + 1].startsWith("दिला")) return true
        }
        return false
    }

    private fun markTrigger(tokens: List<String>, used: MutableSet<Int>) {
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token in triggerWords || token.startsWith("remind")) used.add(i)
            if (token == "yaad" || token == "याद") {
                used.add(i)
                if (i + 1 < tokens.size &&
                    (tokens[i + 1].startsWith("dila") || tokens[i + 1].startsWith("दिला"))
                ) {
                    used.add(i + 1)
                }
            }
            if (token.startsWith("dila") || token.startsWith("दिला")) used.add(i)
        }
    }

    private fun parseRelative(tokens: List<String>, used: MutableSet<Int>, now: Long): Long? {
        for (i in tokens.indices) {
            val number = tokens[i].toIntOrNull() ?: continue
            if (number <= 0) continue
            if (i + 2 < tokens.size) {
                val unit = unitSeconds[tokens[i + 1]]
                if (unit != null && tokens[i + 2] in afterWords) {
                    used.addAll(listOf(i, i + 1, i + 2))
                    return now + number * unit * 1000L
                }
            }
        }
        for (i in tokens.indices) {
            if (tokens[i] !in afterWords) continue
            if (i + 2 >= tokens.size) continue
            val number = tokens[i + 1].toIntOrNull() ?: continue
            if (number <= 0) continue
            val unit = unitSeconds[tokens[i + 2]] ?: continue
            used.addAll(listOf(i, i + 1, i + 2))
            return now + number * unit * 1000L
        }
        return null
    }

    private fun parseClock(tokens: List<String>, used: MutableSet<Int>, now: Long): Long? {
        var hour = -1
        var minute = 0
        var meridian: String? = null

        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.contains(':')) {
                val parts = token.split(':')
                val h = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (h !in 0..23 || m !in 0..59) continue
                hour = h
                minute = m
                used.add(i)
                if (i + 1 < tokens.size && tokens[i + 1] in meridianWords) {
                    meridian = tokens[i + 1]
                    used.add(i + 1)
                } else if (i + 1 < tokens.size && tokens[i + 1] in clockWords) {
                    used.add(i + 1)
                }
                break
            }
            val h = token.toIntOrNull() ?: continue
            if (h !in 0..23) continue

            if (i + 2 < tokens.size) {
                val m = tokens[i + 1].toIntOrNull()
                if (m != null && m in 0..59 && tokens[i + 2] in clockWords) {
                    hour = h
                    minute = m
                    used.addAll(listOf(i, i + 1, i + 2))
                    break
                }
            }
            if (i + 1 < tokens.size && tokens[i + 1] in clockWords) {
                hour = h
                minute = 0
                used.addAll(listOf(i, i + 1))
                break
            }
            if (i + 1 < tokens.size && tokens[i + 1] in meridianWords) {
                hour = h
                minute = 0
                meridian = tokens[i + 1]
                used.addAll(listOf(i, i + 1))
                break
            }
            if (i > 0 && tokens[i - 1] == "at") {
                hour = h
                minute = 0
                used.addAll(listOf(i - 1, i))
                if (i + 1 < tokens.size && tokens[i + 1] in meridianWords) {
                    meridian = tokens[i + 1]
                    used.add(i + 1)
                }
                break
            }
        }

        if (hour < 0) return null

        val daypart = when {
            tokens.any { it in morningWords } -> "am"
            tokens.any { it in afternoonWords || it in eveningWords || it in nightWords } -> "pm"
            else -> null
        }
        val hour24 = resolveHour(hour, meridian, daypart, tokens, now)
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        if (tokens.any { it in dateParsoWords }) {
            calendar.add(Calendar.DAY_OF_YEAR, 2)
        } else if (tokens.any { it in dateTomorrowWords }) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        calendar.set(Calendar.HOUR_OF_DAY, hour24)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        var at = calendar.timeInMillis
        while (at <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            at = calendar.timeInMillis
        }
        return at
    }

    private fun resolveHour(
        hour: Int,
        meridian: String?,
        daypart: String?,
        tokens: List<String>,
        now: Long
    ): Int {
        if (meridian == "pm") return if (hour in 1..11) hour + 12 else 12
        if (meridian == "am") return if (hour == 12) 0 else hour
        if (hour > 12) return hour
        if (hour == 12) {
            if (daypart == "pm" && tokens.any { it in eveningWords || it in nightWords }) return 0
            val current = Calendar.getInstance().apply { timeInMillis = now }
                .get(Calendar.HOUR_OF_DAY)
            return if (current < 12) 12 else 0
        }
        if (daypart == "am") return hour
        if (daypart == "pm") {
            if (tokens.any { it in nightWords } && hour == 12) return 0
            return hour + 12
        }
        val current = Calendar.getInstance().apply { timeInMillis = now }
            .get(Calendar.HOUR_OF_DAY)
        return if (current < 5) hour else hour + 12
    }
}
