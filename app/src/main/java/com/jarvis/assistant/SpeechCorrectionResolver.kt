package com.jarvis.assistant

import java.util.Calendar

object SpeechCorrectionResolver {

    data class TimeValue(
        val hour: Int? = null,
        val minute: Int = 0,
        val meridian: String? = null,
        val daypart: String? = null,
        val night: Boolean = false,
        val relativeMillis: Long? = null,
        val relativePhrase: String = "",
        val display: String,
        val explicitMeridian: Boolean = false
    )

    data class DateValue(
        val kind: Kind,
        val month: Int? = null,
        val day: Int? = null,
        val display: String = ""
    ) {
        enum class Kind { TODAY, TOMORROW, DAY_AFTER, EXPLICIT }
    }

    data class Resolution(
        val hasCorrection: Boolean,
        val segments: List<String>,
        val date: DateValue?,
        val time: TimeValue?,
        val title: String?,
        val cancelled: List<String>,
        val needsClarification: Boolean,
        val clarificationQuestion: String,
        val correctedCommandText: String
    ) {
        fun canonicalReminder(now: Long): String? {
            val value = time ?: return null
            val timePhrase = if (value.relativeMillis != null) {
                value.relativePhrase
            } else {
                val hour = value.hour ?: return null
                val hour24 = resolveHour(hour, value.meridian, value.daypart, value.night, now)
                val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
                val meridian = if (hour24 < 12) "am" else "pm"
                "$hour12:${value.minute.toString().padStart(2, '0')} $meridian"
            }
            val datePhrase = if (value.relativeMillis != null) {
                ""
            } else {
                when (date?.kind) {
                    DateValue.Kind.TODAY -> "aaj"
                    DateValue.Kind.TOMORROW -> "kal"
                    DateValue.Kind.DAY_AFTER -> "parso"
                    DateValue.Kind.EXPLICIT -> date.display
                    null -> ""
                }
            }
            return listOf("reminder", datePhrase, timePhrase, title.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }

    private data class SegmentInfo(
        val time: TimeValue?,
        val date: DateValue?,
        val title: String?
    )

    private val markerRegex = Regex(
        "(?i)(?:\\b(?:mera\\s+matlab|matlab|nahi\\s+nahi|nahin|nahi|i\\s+mean|" +
            "actually|instead|rather|sorry|yaani|yani|correction|galti\\s+se|balki|but|not)\\b|" +
            "मेरा\\s+मतलब|मतलब|नहीं|नहि|असल\\s+में|सॉरी|यानी|गलती\\s+से|बल्कि)"
    )

    private val genericTitles = setOf("hai", "h", "he", "hain", "है", "हैं", "reminder", "रिमाइंडर")

    fun latestInstruction(raw: String): String {
        val (segments, found) = splitSegments(raw)
        if (!found) return raw.trim()
        return segments.lastOrNull()?.ifBlank { raw.trim() } ?: raw.trim()
    }

    fun resolve(raw: String, now: Long = System.currentTimeMillis()): Resolution {
        val (segments, found) = splitSegments(raw)
        var date: DateValue? = null
        var time: TimeValue? = null
        var title: String? = null
        val cancelled = mutableListOf<String>()

        for (segment in segments) {
            val info = analyzeSegment(segment)
            info.date?.let { value ->
                val previous = date
                if (previous != null &&
                    (previous.kind != value.kind || previous.display != value.display)
                ) {
                    cancelled.add(previous.display)
                }
                date = value
            }
            info.time?.let { value ->
                val previous = time
                if (previous != null && previous.display != value.display) {
                    cancelled.add(previous.display)
                }
                time = value
            }
            info.title?.let { value ->
                val previous = title
                if (previous != null && !previous.equals(value, ignoreCase = true)) {
                    cancelled.add(previous)
                }
                title = value
            }
        }

        val value = time
        val ambiguous = value != null &&
            value.relativeMillis == null &&
            !value.explicitMeridian &&
            value.daypart == null &&
            (value.hour ?: 0) in 1..12
        val question = if (ambiguous) {
            val hour = value?.hour ?: 0
            if (hour == 12) "12 baje dopahar ya raat?" else "$hour baje subah ya shaam?"
        } else {
            ""
        }

        return Resolution(
            hasCorrection = found,
            segments = segments,
            date = date,
            time = time,
            title = title,
            cancelled = cancelled,
            needsClarification = ambiguous,
            clarificationQuestion = question,
            correctedCommandText = segments.lastOrNull().orEmpty()
        )
    }

    private fun splitSegments(raw: String): Pair<List<String>, Boolean> {
        val matches = markerRegex.findAll(raw).toList()
        if (matches.isEmpty()) {
            val cleaned = cleanSegment(raw)
            return (if (cleaned.isBlank()) emptyList() else listOf(cleaned)) to false
        }
        val segments = mutableListOf<String>()
        var start = 0
        for (match in matches) {
            val before = cleanSegment(raw.substring(start, match.range.first))
            if (before.isNotBlank()) segments.add(before)
            start = match.range.last + 1
        }
        val tail = cleanSegment(raw.substring(start))
        if (tail.isNotBlank()) segments.add(tail)
        return segments to true
    }

    private fun cleanSegment(value: String): String =
        value.trim().trim(',', '.', ';', ':', '-', '।', '!', '?', ' ').trim()

    private fun analyzeSegment(segment: String): SegmentInfo {
        val tokens = VoiceReminderParser.tokenize(segment)
        if (tokens.isEmpty()) return SegmentInfo(null, null, null)
        val used = mutableSetOf<Int>()
        val date = extractDate(tokens, used)
        val time = extractTime(tokens, used)
        val title = extractTitle(tokens, used)
        return SegmentInfo(time, date, title)
    }

    private fun extractDate(tokens: List<String>, used: MutableSet<Int>): DateValue? {
        for (index in tokens.indices) {
            when {
                tokens[index] in VoiceReminderParser.dateParsoWords -> {
                    used.add(index)
                    return DateValue(DateValue.Kind.DAY_AFTER, display = "parso")
                }
                tokens[index] in VoiceReminderParser.dateTomorrowWords -> {
                    used.add(index)
                    return DateValue(DateValue.Kind.TOMORROW, display = "kal")
                }
                tokens[index] in VoiceReminderParser.dateTodayWords -> {
                    used.add(index)
                    return DateValue(DateValue.Kind.TODAY, display = "aaj")
                }
            }
        }
        val before = used.toSet()
        val explicit = VoiceReminderParser.parseExplicitDate(tokens, used) ?: return null
        val consumed = (used - before).sorted()
        val display = consumed.joinToString(" ") { tokens[it] }
        return DateValue(
            DateValue.Kind.EXPLICIT,
            month = explicit.first,
            day = explicit.second,
            display = display
        )
    }

    private fun extractTime(tokens: List<String>, used: MutableSet<Int>): TimeValue? {
        val daypart = detectDaypart(tokens)
        val night = tokens.any { it in VoiceReminderParser.nightWords }

        for (index in tokens.indices) {
            val number = tokens[index].toIntOrNull() ?: continue
            if (number <= 0) continue
            if (index + 2 < tokens.size) {
                val unit = VoiceReminderParser.unitSeconds[tokens[index + 1]]
                if (unit != null && tokens[index + 2] in VoiceReminderParser.afterWords) {
                    used.addAll(listOf(index, index + 1, index + 2))
                    markDaypart(tokens, used)
                    return TimeValue(
                        daypart = daypart,
                        night = night,
                        relativeMillis = number * unit * 1000L,
                        relativePhrase = "in $number ${pluralUnit(tokens[index + 1], number)}",
                        display = relativeDisplay(number, tokens[index + 1])
                    )
                }
            }
        }

        for (index in tokens.indices) {
            if (tokens[index] !in VoiceReminderParser.afterWords) continue
            if (index + 2 >= tokens.size) continue
            val number = tokens[index + 1].toIntOrNull() ?: continue
            if (number <= 0) continue
            val unit = VoiceReminderParser.unitSeconds[tokens[index + 2]] ?: continue
            used.addAll(listOf(index, index + 1, index + 2))
            markDaypart(tokens, used)
            return TimeValue(
                daypart = daypart,
                night = night,
                relativeMillis = number * unit * 1000L,
                relativePhrase = "in $number ${pluralUnit(tokens[index + 2], number)}",
                display = relativeDisplay(number, tokens[index + 2])
            )
        }

        for (index in tokens.indices) {
            val token = tokens[index]
            if (!token.contains(':')) continue
            val parts = token.split(':')
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) continue
            used.add(index)
            var cursor = index + 1
            if (cursor < tokens.size && tokens[cursor] in VoiceReminderParser.clockWords) {
                used.add(cursor)
                cursor++
            }
            var meridian: String? = null
            if (cursor < tokens.size && tokens[cursor] in VoiceReminderParser.meridianWords) {
                meridian = tokens[cursor]
                used.add(cursor)
            }
            markDaypart(tokens, used)
            return TimeValue(
                hour = hour,
                minute = minute,
                meridian = meridian,
                daypart = daypart,
                night = night,
                display = clockDisplay(hour, minute, meridian),
                explicitMeridian = meridian != null
            )
        }

        for (index in tokens.indices) {
            val hour = tokens[index].toIntOrNull() ?: continue
            if (hour !in 0..23) continue

            if (index + 2 < tokens.size) {
                val minute = tokens[index + 1].toIntOrNull()
                if (minute != null && minute in 0..59) {
                    if (tokens[index + 2] in VoiceReminderParser.clockWords) {
                        used.addAll(listOf(index, index + 1, index + 2))
                        var meridian: String? = null
                        if (index + 3 < tokens.size &&
                            tokens[index + 3] in VoiceReminderParser.meridianWords
                        ) {
                            meridian = tokens[index + 3]
                            used.add(index + 3)
                        }
                        markDaypart(tokens, used)
                        return TimeValue(
                            hour = hour,
                            minute = minute,
                            meridian = meridian,
                            daypart = daypart,
                            night = night,
                            display = clockDisplay(hour, minute, meridian),
                            explicitMeridian = meridian != null
                        )
                    }
                    if (tokens[index + 2] in VoiceReminderParser.meridianWords) {
                        used.addAll(listOf(index, index + 1, index + 2))
                        markDaypart(tokens, used)
                        return TimeValue(
                            hour = hour,
                            minute = minute,
                            meridian = tokens[index + 2],
                            daypart = daypart,
                            night = night,
                            display = clockDisplay(hour, minute, tokens[index + 2]),
                            explicitMeridian = true
                        )
                    }
                }
            }

            if (index + 1 < tokens.size && tokens[index + 1] in VoiceReminderParser.clockWords) {
                used.addAll(listOf(index, index + 1))
                var meridian: String? = null
                if (index + 2 < tokens.size &&
                    tokens[index + 2] in VoiceReminderParser.meridianWords
                ) {
                    meridian = tokens[index + 2]
                    used.add(index + 2)
                }
                markDaypart(tokens, used)
                return TimeValue(
                    hour = hour,
                    minute = 0,
                    meridian = meridian,
                    daypart = daypart,
                    night = night,
                    display = clockDisplay(hour, 0, meridian),
                    explicitMeridian = meridian != null
                )
            }

            if (index + 1 < tokens.size && tokens[index + 1] in VoiceReminderParser.meridianWords) {
                used.addAll(listOf(index, index + 1))
                markDaypart(tokens, used)
                return TimeValue(
                    hour = hour,
                    minute = 0,
                    meridian = tokens[index + 1],
                    daypart = daypart,
                    night = night,
                    display = clockDisplay(hour, 0, tokens[index + 1]),
                    explicitMeridian = true
                )
            }

            if (index > 0 && tokens[index - 1] == "at") {
                used.addAll(listOf(index - 1, index))
                var meridian: String? = null
                if (index + 1 < tokens.size &&
                    tokens[index + 1] in VoiceReminderParser.meridianWords
                ) {
                    meridian = tokens[index + 1]
                    used.add(index + 1)
                }
                markDaypart(tokens, used)
                return TimeValue(
                    hour = hour,
                    minute = 0,
                    meridian = meridian,
                    daypart = daypart,
                    night = night,
                    display = clockDisplay(hour, 0, meridian),
                    explicitMeridian = meridian != null
                )
            }
        }
        return null
    }

    private fun extractTitle(tokens: List<String>, used: MutableSet<Int>): String? {
        val content = tokens.filterIndexed { index, token ->
            index !in used &&
                token !in VoiceReminderParser.triggerWords &&
                !token.startsWith("remind") &&
                token !in VoiceReminderParser.clockWords &&
                token !in VoiceReminderParser.meridianWords &&
                token !in VoiceReminderParser.meridianParts &&
                token !in VoiceReminderParser.afterWords &&
                token !in VoiceReminderParser.dateTomorrowWords &&
                token !in VoiceReminderParser.dateTodayWords &&
                token !in VoiceReminderParser.dateParsoWords &&
                token !in VoiceReminderParser.morningWords &&
                token !in VoiceReminderParser.afternoonWords &&
                token !in VoiceReminderParser.eveningWords &&
                token !in VoiceReminderParser.nightWords &&
                token !in VoiceReminderParser.fillerWords &&
                token !in VoiceReminderParser.monthWords.keys &&
                token !in VoiceReminderParser.unitSeconds.keys
        }
        val rawTitle = content.joinToString(" ").trim()
        if (rawTitle.isBlank() || rawTitle.lowercase() in genericTitles) return null
        return rawTitle.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
    }

    private fun detectDaypart(tokens: List<String>): String? = when {
        tokens.any { it in VoiceReminderParser.morningWords } -> "am"
        tokens.any {
            it in VoiceReminderParser.afternoonWords ||
                it in VoiceReminderParser.eveningWords ||
                it in VoiceReminderParser.nightWords
        } -> "pm"
        else -> null
    }

    private fun markDaypart(tokens: List<String>, used: MutableSet<Int>) {
        for (index in tokens.indices) {
            if (tokens[index] in VoiceReminderParser.morningWords ||
                tokens[index] in VoiceReminderParser.afternoonWords ||
                tokens[index] in VoiceReminderParser.eveningWords ||
                tokens[index] in VoiceReminderParser.nightWords
            ) {
                used.add(index)
            }
        }
    }

    private fun clockDisplay(hour: Int, minute: Int, meridian: String?): String {
        val base = hour.toString().padStart(2, '0') +
            ":" + minute.toString().padStart(2, '0')
        return if (meridian == null) base else "$base ${meridian.uppercase()}"
    }

    private fun relativeDisplay(number: Int, unit: String): String {
        val name = pluralUnit(unit, number)
        return if (name == "minute" || name == "hour" || name == "second") {
            "$number $name"
        } else {
            "$number ${unit.replaceFirstChar { it }}"
        }
    }

    private fun pluralUnit(token: String, number: Int): String = when {
        token.startsWith("hour") || token == "ghanta" || token == "ghante" ||
            token == "घंटा" || token == "घंटे" -> if (number == 1) "hour" else "hours"
        token.startsWith("second") || token == "sec" || token == "secs" ||
            token == "सेकंड" -> if (number == 1) "second" else "seconds"
        else -> if (number == 1) "minute" else "minutes"
    }

    private fun resolveHour(
        hour: Int,
        meridian: String?,
        daypart: String?,
        night: Boolean,
        now: Long
    ): Int {
        if (meridian == "pm") return if (hour in 1..11) hour + 12 else 12
        if (meridian == "am") return if (hour == 12) 0 else hour
        if (hour > 12) return hour
        if (hour == 12) {
            if (daypart == "pm" && night) return 0
            val current = Calendar.getInstance().apply { timeInMillis = now }
                .get(Calendar.HOUR_OF_DAY)
            return if (current < 12) 12 else 0
        }
        if (daypart == "am") return hour
        if (daypart == "pm") return hour + 12
        val current = Calendar.getInstance().apply { timeInMillis = now }
            .get(Calendar.HOUR_OF_DAY)
        return if (current < 5) hour else hour + 12
    }
}
