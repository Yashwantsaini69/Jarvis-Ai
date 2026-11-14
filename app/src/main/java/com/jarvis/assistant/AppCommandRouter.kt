package com.jarvis.assistant

object AppCommandRouter {
    enum class Action {
        OPEN_CHAT,
        CLOSE_CHAT,
        CLEAR_CHAT,
        OPEN_SETTINGS,
        OPEN_MEMORY,
        OPEN_NOTES,
        OPEN_MULTIBRAIN,
        CYCLE_ORB,
        MUTE_MIC,
        UNMUTE_MIC,
        BACK
    }

    private val openWords = setOf(
        "open", "kholo", "khol", "kholna", "kholiye", "chalu", "start",
        "show", "dikhao", "dikha", "launch", "activate", "on",
        "karo", "kar", "kardo", "kardiya",
        "खोलो", "खोल", "चालू", "शुरू", "दिखाओ", "दिखा",
        "ऑन", "करो", "कर", "करदो", "करदिया"
    )
    private val closeWords = setOf(
        "close", "band", "off", "hide", "hatao", "hata", "stop", "exit",
        "back", "wapas", "wapis",
        "बंद", "हटाओ", "वापस", "पीछे"
    )
    private val clearWords = setOf(
        "delete", "clear", "remove", "erase", "dlt", "mita", "mitao", "mitado",
        "साफ", "मिटा", "मिटाओ", "डिलीट"
    )
    private val changeWords = setOf(
        "change", "switch", "next", "badlo", "badal", "badla", "agle", "agla",
        "बदलो", "बदल", "अगला"
    )
    private val micWords = setOf("mic", "mike", "microphone", "माइक", "माइक्रोफोन")
    private val chatWords = setOf("chat", "transcript", "conversation", "चैट", "चाट")
    private val settingsWords = setOf(
        "settings", "setting", "config", "configuration", "preferences",
        "सेटिंग", "सेटिंग्स"
    )
    private val memoryWords = setOf("memory", "memories", "yaad", "yaadein", "मेमोरी", "याद")
    private val noteWords = setOf(
        "note", "notes", "notepad", "reminder", "reminders",
        "नोट", "नोट्स", "नोटपैड", "रिमाइंडर"
    )
    private val orbWords = setOf("orb", "theme", "style", "look", "ऑर्ब", "थीम")

    fun parse(raw: String): Action? {
        val cleaned = raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        val words = cleaned.split(' ').filter { it.isNotEmpty() }.toSet()
        fun any(set: Set<String>) = set.any { words.contains(it) }

        val wantsOpen = any(openWords)
        val wantsClose = any(closeWords)
        val wantsClear = any(clearWords)
        val wantsChange = any(changeWords)
        val isMic = any(micWords)
        val isChat = any(chatWords)
        val isSettings = any(settingsWords)
        val isMemory = any(memoryWords)
        val isNotes = any(noteWords)
        val isOrb = any(orbWords)
        val isMultiBrain = cleaned.contains("multi brain") ||
            words.contains("multibrain") ||
            (words.contains("brain") && (words.contains("manager") || words.contains("managers")))

        if (words.contains("unmute")) return Action.UNMUTE_MIC
        if (words.contains("mute") || words.contains("muted")) return Action.MUTE_MIC

        if (isOrb && wantsChange) return Action.CYCLE_ORB

        if (isMic) {
            if (wantsClose) return Action.MUTE_MIC
            if (wantsOpen) return Action.UNMUTE_MIC
            return null
        }
        if (isMultiBrain) {
            if (wantsClose) return Action.BACK
            if (wantsOpen) return Action.OPEN_MULTIBRAIN
            return null
        }
        if (isChat) {
            if (wantsClear) return Action.CLEAR_CHAT
            if (wantsClose) return Action.CLOSE_CHAT
            if (wantsOpen) return Action.OPEN_CHAT
            return null
        }
        if (isSettings) {
            if (wantsOpen && !wantsClose) return Action.OPEN_SETTINGS
            if (wantsClose) return Action.BACK
            return null
        }
        if (isMemory) {
            if (wantsOpen && !wantsClose) return Action.OPEN_MEMORY
            if (wantsClose) return Action.BACK
            return null
        }
        if (isNotes) {
            if (wantsOpen && !wantsClose) return Action.OPEN_NOTES
            if (wantsClose) return Action.BACK
            return null
        }
        if (wantsClose) return Action.BACK
        return null
    }
}
