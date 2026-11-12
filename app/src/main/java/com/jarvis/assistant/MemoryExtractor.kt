package com.jarvis.assistant

object MemoryExtractor {

    data class MemoryEntry(
        val type: String,
        val key: String,
        val value: String,
        val importance: Int,
        val confidence: Double
    )

    sealed class Command {
        data class Save(val entry: MemoryEntry) : Command()
        data class Forget(val query: String) : Command()
        object Clear : Command()
    }

    private val CLEAR_ALL = Regex(
        "(?i)\\b(?:forget\\s+(?:everything|all(?:\\s+memories)?)|" +
            "clear\\s+(?:all\\s+)?memor(?:y|ies)|" +
            "sab\\s+kuch\\s+bhool\\s+jao|sab\\s+bhool\\s+jao|" +
            "saari\\s+memory\\s+(?:delete|hatao|clear)|sari\\s+memory\\s+(?:delete|hatao|clear))\\b"
    )

    private val FORGET_EN = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:forget|delete|remove|erase)\\s+" +
            "(?:(?:the\\s+)?memory\\s+(?:of|about|regarding)\\s+|memory\\s+(?:of|about)\\s+|" +
            "that\\s+|about\\s+|my\\s+|mera\\s+|meri\\s+)?(.+?)\\s*$"
    )

    private val FORGET_HI = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:bhool\\s+jao|bhul\\s+jao|bhool\\s+do|" +
            "yaad\\s+se\\s+(?:hatao|mita\\s+do|delete\\s+karo)|memory\\s+(?:delete|hatao|clear)\\s+karo)" +
            "\\s*(?:ki\\s+|that\\s+)?(.+?)\\s*$"
    )

    private val FORGET_HI_TRAILING = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:(?:my|mera|meri|mujhe|muje)\\s+)?" +
            "(.+?)\\s+(?:ko\\s+)?(?:bhool\\s+jao|bhul\\s+jao|bhool\\s+do|bhul\\s+do|" +
            "yaad\\s+se\\s+(?:hatao|mita\\s+do)|delete\\s+karo|hatao)\\s*$"
    )

    private val REMEMBER = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:remember(?:\\s+this)?(?:\\s+that)?|note\\s+that|save\\s+this|" +
            "yaad\\s+rakhna|yaad\\s+rakho|yaad\\s+rakh|dhyaan\\s+rakhna|dhyan\\s+rakhna)" +
            "\\s*(?:ki|that)?\\s*[:,-]?\\s*(.+?)\\s*$"
    )

    private val NAME_EN = Regex(
        "(?i)\\b(?:my\\s+name\\s+is|my\\s+name's|call\\s+me|i\\s+am\\s+called|i'm\\s+called)\\s+" +
            "(?:actually\\s+)?([A-Za-z][A-Za-z'.-]*(?:\\s+[A-Za-z][A-Za-z'.-]*){0,3})"
    )

    private val NAME_HI = Regex(
        "(?i)\\b(?:mera|meri)\\s+naam\\s+(?:actually\\s+|asal\\s+me\\s+)?(.+?)\\s+(?:hai|h|he|hain)\\b"
    )

    private val NICK_HI = Regex(
        "(?i)\\b(?:mujhe|muje|mujhko)\\s+(.+?)\\s+(?:bulate|bulate\\s+hain|bolte|bolte\\s+hain)\\b"
    )

    private val CITY_EN = Regex(
        "(?i)\\b(?:i\\s+live\\s+in|i\\s+am\\s+from|i'm\\s+from|i\\s+stay\\s+in)\\s+([^.,!?;]+)"
    )

    private val CITY_HI = Regex(
        "(?i)\\b(?:main|mai|mein)\\s+([^.,!?;]+?)\\s+(?:me|mein|mai)\\s+" +
            "(?:rehta|rehti|rahata|rahati)\\s+(?:hu|hun|hoon)\\b"
    )

    private val CITY_HI2 = Regex(
        "(?i)\\bmera\\s+(?:city|shehar|shahar|gaon)\\s+(.+?)\\s+(?:hai|h|he)\\b"
    )

    private val BIRTHDAY = Regex(
        "(?i)\\b(?:my\\s+birthday\\s+is|mera\\s+birthday|meri\\s+janmadin)\\s+(?:is\\s+)?([^.,!?;]+)"
    )

    private val WORK_EN = Regex(
        "(?i)\\b(?:i\\s+work\\s+(?:at|for|in)|i'm\\s+working\\s+(?:at|for))\\s+([^.,!?;]+)"
    )

    private val WORK_HI = Regex(
        "(?i)\\b(?:main|mai|mein)\\s+([^.,!?;]+?)\\s+(?:me|mein|mai)\\s+" +
            "(?:kaam|kam)\\s+(?:karta|karti)\\s+(?:hu|hun|hoon)\\b"
    )

    private val STUDY_EN = Regex(
        "(?i)\\b(?:i\\s+study\\s+at|i'm\\s+studying\\s+at)\\s+([^.,!?;]+)"
    )

    private val STUDY_HI = Regex(
        "(?i)\\b(?:main|mai|mein)\\s+([^.,!?;]+?)\\s+(?:me|mein|mai)\\s+" +
            "(?:padhta|padhti)\\s+(?:hu|hun|hoon)\\b"
    )

    private val DISLIKE_EN = Regex(
        "(?i)\\bi\\s+(?:really\\s+)?(?:don'?t\\s+like|do\\s+not\\s+like|hate|dislike)\\s+([^.,!?;]+)"
    )

    private val DISLIKE_HI = Regex(
        "(?i)\\bmujhe\\s+([^.,!?;]+?)\\s+pasand\\s+(?:nahi|nhi|na)\\s+(?:hai|h|he)\\b"
    )

    private val LIKE_EN = Regex(
        "(?i)\\bi\\s+(?:really\\s+|also\\s+)?(?:like|love|enjoy)\\s+([^.,!?;]+)"
    )

    private val LIKE_HI = Regex(
        "(?i)\\bmujhe\\s+([^.,!?;]+?)\\s+(?:bahut\\s+|bohot\\s+)?pasand\\s+(?:hai|h|he)\\b"
    )

    private val PROJECT_EN = Regex(
        "(?i)\\b(?:i\\s+am\\s+building|i'm\\s+building|i\\s+am\\s+working\\s+on|" +
            "i'm\\s+working\\s+on|my\\s+project\\s+is)\\s+([^.,!?;]+)"
    )

    private val PROJECT_HI = Regex(
        "(?i)\\b(?:main|mai|mein)\\s+([^.,!?;]+?)\\s+(?:bana|banaa|ban)\\s+" +
            "(?:raha|rahi)\\s+(?:hu|hun|hoon)\\b"
    )

    private val PROJECT_HI2 = Regex(
        "(?i)\\bmera\\s+project\\s+(.+?)\\s+(?:hai|h|he)\\b"
    )

    private val HEDGES = Regex(
        "(?i)\\b(?:maybe|perhaps|probably|possibly|i\\s+think|i\\s+guess|not\\s+sure|" +
            "i\\s+might|i\\s+may|shayad|shaayad|lagta\\s+hai|lag\\s+raha\\s+hai|" +
            "pata\\s+nahi|ho\\s+sakta\\s+hai)\\b"
    )

    private val QUESTION_START = Regex(
        "(?i)^\\s*(?:what|who|where|when|why|how|which|do|does|did|can|could|would|will|" +
            "should|are|is|am|have|has|kya|kaun|kahan|kahaan|kab|kaise|kitna|kitni|tum|aap)\\b"
    )

    private val CONJUNCTION_SPLIT = Regex(
        "(?i)\\s+(?:and|but|aur|lekin)\\s+" +
            "(?=(?:i|i'm|my|mera|meri|mujhe|muje|main|mai|mein)\\b)"
    )

    private val SENSITIVE_TOPICS = Regex(
        "(?i)\\b(?:password|passcode|pass\\s*code|pin\\b|otp\\b|" +
            "one[\\s-]?time\\s*(?:password|code|pin)?|2fa\\b|two[\\s-]?factor|cvv\\b|cvc\\b|" +
            "card\\s*(?:number|no|details)|credit\\s*card|debit\\s*card|" +
            "account\\s*(?:number|no|details)|ifsc\\b|upi\\s*pin|api[\\s_-]?key|" +
            "access[\\s_-]?token|auth(?:entication)?[\\s_-]?token|bearer\\b|secret\\s*key|" +
            "client\\s*secret|private\\s*key|seed\\s*phrase|mnemonic|aadhaar|aadhar|" +
            "social\\s*security|ssn\\b|bank\\s*details|net\\s*banking)\\b"
    )

    private val TOKEN_LIKE = Regex(
        "(?i)\\b(?:AIza[0-9A-Za-z_\\-]{8,}|sk-[A-Za-z0-9]{8,}|ghp_[A-Za-z0-9]{8,}|" +
            "xox[baprs]-[A-Za-z0-9\\-]{8,}|eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{4,})\\b"
    )

    private val CARD_LIKE = Regex("\\b(?:\\d[\\s-]?){13,19}\\b")

    private val AADHAAR_LIKE = Regex("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b")

    private val SECRET_WITH_DIGITS = Regex(
        "(?i)\\b(?:otp|code|pin|password|cvv|pin\\s*code)\\b\\D{0,12}\\d{4,10}\\b"
    )

    private val NAME_STOPWORDS = setOf(
        "later", "tomorrow", "now", "please", "soon", "back", "there", "here",
        "again", "buddy", "babe", "friend", "bro", "not", "and"
    )

    fun extract(rawText: String): List<Command> {
        val text = rawText.trim()
        if (text.length < 3) return emptyList()
        val commands = mutableListOf<Command>()

        if (CLEAR_ALL.containsMatchIn(text)) {
            commands.add(Command.Clear)
            return commands
        }

        for (sentence in splitSentences(text)) {
            if (sentence.length < 3) continue
            if (isSensitive(sentence)) continue

            val forget = forgetQuery(sentence)
            if (forget != null) {
                if (forget.length >= 2) commands.add(Command.Forget(forget))
                continue
            }

            val remember = REMEMBER.find(sentence)
            if (remember != null) {
                val content = remember.groupValues.getOrNull(1)?.trim().orEmpty()
                if (content.isNotBlank() && !isSensitive(content)) {
                    val slots = extractSlots(content, 5, 0.97)
                    if (slots.isNotEmpty()) {
                        commands.addAll(slots)
                    } else {
                        val note = cleanValue(content)
                        if (note.length >= 2 && !isSensitiveValue(note)) {
                            commands.add(
                                Command.Save(
                                    MemoryEntry(
                                        "note",
                                        "note:" + slugify(note),
                                        note.take(160),
                                        5,
                                        0.97
                                    )
                                )
                            )
                        }
                    }
                }
                continue
            }

            if (isQuestion(sentence)) continue
            if (HEDGES.containsMatchIn(sentence)) continue

            commands.addAll(extractSlots(sentence, null, 0.9))
        }

        return dedupe(commands)
    }

    fun isSensitive(text: String): Boolean =
        SENSITIVE_TOPICS.containsMatchIn(text) ||
            TOKEN_LIKE.containsMatchIn(text) ||
            CARD_LIKE.containsMatchIn(text) ||
            AADHAAR_LIKE.containsMatchIn(text) ||
            SECRET_WITH_DIGITS.containsMatchIn(text)

    fun isSensitiveValue(value: String): Boolean =
        SENSITIVE_TOPICS.containsMatchIn(value) ||
            TOKEN_LIKE.containsMatchIn(value) ||
            CARD_LIKE.containsMatchIn(value) ||
            AADHAAR_LIKE.containsMatchIn(value) ||
            SECRET_WITH_DIGITS.containsMatchIn(value)

    fun slugify(value: String): String =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
            .ifEmpty { "entry" }

    private fun extractSlots(
        text: String,
        forcedImportance: Int?,
        confidence: Double
    ): List<Command> {
        val out = mutableListOf<Command>()

        fun add(type: String, key: String, raw: String?, baseImportance: Int) {
            if (raw == null) return
            val value = cleanValue(raw)
            if (value.length < 2 || value.length > 160) return
            if (isSensitiveValue(value)) return
            if (type == "identity" && key == "name" && NAME_STOPWORDS.contains(value.lowercase())) return
            out.add(
                Command.Save(
                    MemoryEntry(type, key, value, forcedImportance ?: baseImportance, confidence)
                )
            )
        }

        fun addPreference(prefix: String, raw: String?) {
            if (raw == null) return
            val value = cleanValue(raw)
            if (value.length < 2 || value.length > 160) return
            if (isSensitiveValue(value)) return
            out.add(
                Command.Save(
                    MemoryEntry("preference", prefix + slotFor(value), value, forcedImportance ?: 3, confidence)
                )
            )
        }

        fun addProject(raw: String?) {
            if (raw == null) return
            val value = cleanValue(raw)
            if (value.length < 2 || value.length > 160) return
            if (isSensitiveValue(value)) return
            out.add(
                Command.Save(
                    MemoryEntry("project", "project:" + slotFor(value), value, forcedImportance ?: 4, confidence)
                )
            )
        }

        add("identity", "name", capture(text, NAME_EN), 5)
        add("identity", "name", capture(text, NAME_HI), 5)
        add("identity", "name", capture(text, NICK_HI), 5)
        add("location", "city", capture(text, CITY_EN), 4)
        add("location", "city", capture(text, CITY_HI), 4)
        add("location", "city", capture(text, CITY_HI2), 4)
        add("identity", "birthday", capture(text, BIRTHDAY), 5)
        add("work", "employer", capture(text, WORK_EN), 4)
        add("work", "employer", capture(text, WORK_HI), 4)
        add("education", "school", capture(text, STUDY_EN), 4)
        add("education", "school", capture(text, STUDY_HI), 4)

        addPreference("dislike:", capture(text, DISLIKE_EN))
        addPreference("dislike:", capture(text, DISLIKE_HI))
        addPreference("like:", capture(text, LIKE_EN))
        addPreference("like:", capture(text, LIKE_HI))

        addProject(capture(text, PROJECT_EN))
        addProject(capture(text, PROJECT_HI))
        addProject(capture(text, PROJECT_HI2))

        return out
    }

    private fun capture(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun forgetQuery(sentence: String): String? {
        FORGET_EN.find(sentence)?.let { return it.groupValues.getOrNull(1)?.trim() }
        FORGET_HI.find(sentence)?.let { return it.groupValues.getOrNull(1)?.trim() }
        FORGET_HI_TRAILING.find(sentence)?.let { return it.groupValues.getOrNull(1)?.trim() }
        return null
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        for (part in text.split(Regex("[.!?।;\\n]+"))) {
            for (clause in part.split(CONJUNCTION_SPLIT)) {
                val trimmed = clause.trim()
                if (trimmed.isNotEmpty()) out.add(trimmed)
            }
        }
        return out
    }

    private fun isQuestion(sentence: String): Boolean =
        sentence.trim().endsWith("?") || QUESTION_START.containsMatchIn(sentence)

    private fun cleanValue(raw: String): String {
        var value = raw.trim()
            .trim('"', '\'', '`', '“', '”')
            .replace(Regex("\\s+"), " ")
        value = value.replace(Regex("(?i)\\s+(?:hai|hain|h|he|tha|thi|the)\\s*$"), "")
        value = value.trim().trimEnd('.', ',', '!', '?', ';', ':', '।', '"', '\'')
        if (value.length > 160) value = value.take(160).trimEnd()
        return value
    }

    private fun slotFor(value: String): String =
        slugify(value).replace(" ", "-").ifEmpty { "entry" }

    private fun dedupe(commands: List<Command>): List<Command> {
        val seen = HashSet<String>()
        val out = mutableListOf<Command>()
        for (command in commands) {
            val signature = when (command) {
                is Command.Save -> "s:${command.entry.type}:${command.entry.key}"
                is Command.Forget -> "f:${command.query.lowercase()}"
                Command.Clear -> "c"
            }
            if (seen.add(signature)) out.add(command)
        }
        return out
    }
}
