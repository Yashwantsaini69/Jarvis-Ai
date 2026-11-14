package com.jarvis.assistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object VoiceReminderParser {

    data class Parsed(
        val title: String,
        val body: String,
        val atMillis: Long,
        val cancelledInstructions: List<String> = emptyList(),
        val needsClarification: Boolean = false,
        val clarificationQuestion: String = "",
        val corrected: Boolean = false
    ) {
        fun toJson(): String {
            fun esc(value: String): String = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(atMillis))
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(atMillis))
            val cancelled = cancelledInstructions.joinToString(",") { "\"${esc(it)}\"" }
            return "{\"intent\":\"set_reminder\"," +
                "\"title\":\"${esc(title)}\"," +
                "\"date\":\"$date\"," +
                "\"time\":\"$time\"," +
                "\"cancelled_instructions\":[$cancelled]," +
                "\"needs_clarification\":$needsClarification}"
        }
    }

    internal val triggerWords = setOf(
        "reminder", "reminders", "remind", "alarm", "alert",
        "wake", "wakeup", "uthana", "uthna", "jagana", "jagna", "jaga",
        "रिमाइंडर", "अलार्म", "एलर्ट", "उठाना", "उठना", "जगाना"
    )

    internal val clockWords = setOf("baje", "baj", "oclock", "o'clock", "बजे")

    internal val meridianWords = setOf("am", "pm")
    internal val meridianParts = setOf("a", "p", "m")

    internal val afterWords = setOf(
        "baad", "bad", "after", "in", "me", "बाद", "में", "मे"
    )

    internal val dateTomorrowWords = setOf(
        "kal", "tomorrow", "tommorow", "tomorow", "tmrw", "2moro", "कल"
    )
    internal val dateTodayWords = setOf("aaj", "today", "आज")
    internal val dateParsoWords = setOf("parso", "parson", "परसों")

    internal val monthWords: Map<String, Int> = mapOf(
        "jan" to Calendar.JANUARY, "january" to Calendar.JANUARY,
        "feb" to Calendar.FEBRUARY, "february" to Calendar.FEBRUARY,
        "mar" to Calendar.MARCH, "march" to Calendar.MARCH,
        "apr" to Calendar.APRIL, "april" to Calendar.APRIL,
        "may" to Calendar.MAY,
        "jun" to Calendar.JUNE, "june" to Calendar.JUNE,
        "jul" to Calendar.JULY, "july" to Calendar.JULY,
        "aug" to Calendar.AUGUST, "august" to Calendar.AUGUST,
        "sep" to Calendar.SEPTEMBER, "sept" to Calendar.SEPTEMBER,
        "september" to Calendar.SEPTEMBER,
        "oct" to Calendar.OCTOBER, "october" to Calendar.OCTOBER,
        "nov" to Calendar.NOVEMBER, "november" to Calendar.NOVEMBER,
        "dec" to Calendar.DECEMBER, "december" to Calendar.DECEMBER,
        "जनवरी" to Calendar.JANUARY, "फरवरी" to Calendar.FEBRUARY,
        "मार्च" to Calendar.MARCH, "अप्रैल" to Calendar.APRIL,
        "मई" to Calendar.MAY, "जून" to Calendar.JUNE, "जुलाई" to Calendar.JULY,
        "अगस्त" to Calendar.AUGUST, "सितंबर" to Calendar.SEPTEMBER,
        "अक्टूबर" to Calendar.OCTOBER, "नवंबर" to Calendar.NOVEMBER,
        "दिसंबर" to Calendar.DECEMBER
    )

    internal val morningWords = setOf("subah", "morning", "सुबह")
    internal val afternoonWords = setOf("dopahar", "afternoon", "दोपहर")
    internal val eveningWords = setOf("shaam", "sham", "evening", "शाम")
    internal val nightWords = setOf("raat", "rat", "night", "रात")

    internal val unitSeconds: Map<String, Long> = mapOf(
        "second" to 1L, "seconds" to 1L, "sec" to 1L, "secs" to 1L, "सेकंड" to 1L,
        "minute" to 60L, "minutes" to 60L, "min" to 60L, "mins" to 60L, "मिनट" to 60L,
        "hour" to 3600L, "hours" to 3600L, "ghanta" to 3600L, "ghante" to 3600L,
        "घंटा" to 3600L, "घंटे" to 3600L
    )

    internal val fillerWords = setOf(
        "please", "pls", "plz", "me", "to", "at", "on", "for", "set", "lagao",
        "lagana", "lagado", "laga", "lag", "kar", "karo", "kardo", "do", "de",
        "dijiye", "mujhe", "muje", "mereko", "merko", "meko", "mera", "meri",
        "ka", "ke", "ki", "liye", "liy", "of",
        "pe", "par", "ko", "se", "tak", "time", "dena", "deni", "dunga", "dungi",
        "denge", "dilana", "yaad", "dila", "dilao", "dilado", "in", "up",
        "remind", "मुझे", "मेरा", "मेरी", "का", "के", "की", "लिए", "याद", "दिला",
        "दिलाओ", "लगाओ", "करो", "कर", "दो", "सेट", "पे", "पर", "को", "से", "तक",
        "समय", "देना", "दूंगा", "दूंगी"
    )

    fun hasTrigger(raw: String): Boolean {
        if (isReminderRequest(tokenize(raw))) return true
        val resolution = SpeechCorrectionResolver.resolve(raw)
        return resolution.hasCorrection && resolution.time != null && resolution.title != null
    }

    fun parse(raw: String, now: Long = System.currentTimeMillis()): Parsed? {
        if (!hasTrigger(raw)) return null
        val resolution = SpeechCorrectionResolver.resolve(raw, now)
        val parsed = if (resolution.hasCorrection) {
            resolution.canonicalReminder(now)?.let { parseLegacy(it, now) }
        } else {
            parseLegacy(raw, now)
        } ?: return null
        return parsed.copy(
            cancelledInstructions = resolution.cancelled,
            needsClarification = resolution.needsClarification,
            clarificationQuestion = resolution.clarificationQuestion,
            corrected = resolution.hasCorrection
        )
    }

    private fun parseLegacy(raw: String, now: Long): Parsed? {
        val tokens = tokenize(raw)

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
                token !in meridianParts &&
                token !in afterWords &&
                token !in dateTomorrowWords &&
                token !in dateTodayWords &&
                token !in dateParsoWords &&
                token !in morningWords &&
                token !in afternoonWords &&
                token !in eveningWords &&
                token !in nightWords &&
                token !in fillerWords &&
                token !in monthWords.keys &&
                token !in unitSeconds.keys
        }
        val rawTitle = content.joinToString(" ").trim()
        val title = if (rawTitle.lowercase() in genericTitles) "Reminder" else rawTitle.ifEmpty { "Reminder" }
        val pretty = title.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        return Parsed(pretty, pretty, at)
    }

    private val genericTitles = setOf("hai", "h", "he", "hain", "है", "हैं")

    internal fun tokenize(raw: String): List<String> {
        return raw.lowercase()
            .replace(Regex("(\\d+)(st|nd|rd|th)\\b"), "$1")
            .replace(Regex("(\\d):(\\d{2})(am|pm)\\b"), "$1:$2 $3")
            .replace(Regex("(\\d)(am|pm)\\b"), "$1 $2")
            .replace(Regex("(\\d)[.](\\d{2})\\b"), "$1:$2")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}:/]+"), " ")
            .replace(Regex("\\b([ap])\\s+(m)\\b"), "$1m")
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
        for (i in 0 until tokens.size - 1) {
            val number = tokens[i].toIntOrNull() ?: continue
            if (number <= 0) continue
            val unit = unitSeconds[tokens[i + 1]] ?: continue
            used.addAll(listOf(i, i + 1))
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
                    if (i + 2 < tokens.size && tokens[i + 2] in meridianWords) {
                        meridian = tokens[i + 2]
                        used.add(i + 2)
                    }
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
                    if (i + 3 < tokens.size && tokens[i + 3] in meridianWords) {
                        meridian = tokens[i + 3]
                        used.add(i + 3)
                    }
                    break
                }
                if (m != null && m in 0..59 && tokens[i + 2] in meridianWords) {
                    hour = h
                    minute = m
                    meridian = tokens[i + 2]
                    used.addAll(listOf(i, i + 1, i + 2))
                    break
                }
            }
            if (i + 1 < tokens.size && tokens[i + 1] in clockWords) {
                hour = h
                minute = 0
                used.addAll(listOf(i, i + 1))
                if (i + 2 < tokens.size && tokens[i + 2] in meridianWords) {
                    meridian = tokens[i + 2]
                    used.add(i + 2)
                }
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
        val explicitDate = parseExplicitDate(tokens, used)
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        if (explicitDate != null) {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.MONTH, explicitDate.first)
            calendar.set(Calendar.DAY_OF_MONTH, explicitDate.second)
        } else if (tokens.any { it in dateParsoWords }) {
            calendar.add(Calendar.DAY_OF_YEAR, 2)
        } else if (tokens.any { it in dateTomorrowWords }) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        calendar.set(Calendar.HOUR_OF_DAY, hour24)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        var at = calendar.timeInMillis
        if (explicitDate != null) {
            if (at <= now) {
                calendar.add(Calendar.YEAR, 1)
                at = calendar.timeInMillis
            }
        } else {
            while (at <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                at = calendar.timeInMillis
            }
        }
        return at
    }

    internal fun parseExplicitDate(tokens: List<String>, used: MutableSet<Int>): Pair<Int, Int>? {
        for (i in tokens.indices) {
            val numeric = tokens[i].split('/')
            if (numeric.size == 2) {
                val day = numeric[0].toIntOrNull()
                val month = numeric[1].toIntOrNull()
                if (day != null && day in 1..31 && month != null && month in 1..12) {
                    used.add(i)
                    return (month - 1) to day
                }
            }
        }
        for (i in tokens.indices) {
            val month = monthWords[tokens[i]] ?: continue
            var day = -1
            var dayIndex = -1
            if (i > 0) {
                val previous = tokens[i - 1].toIntOrNull()
                if (previous != null && previous in 1..31) {
                    day = previous
                    dayIndex = i - 1
                }
            }
            if (day < 0 && i + 1 < tokens.size) {
                val next = tokens[i + 1].toIntOrNull()
                if (next != null && next in 1..31) {
                    day = next
                    dayIndex = i + 1
                }
            }
            if (day > 0) {
                used.add(i)
                used.add(dayIndex)
                return month to day
            }
        }
        return null
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
