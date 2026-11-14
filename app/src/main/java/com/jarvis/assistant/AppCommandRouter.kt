package com.jarvis.assistant

object AppCommandRouter {
    enum class Action {
        OPEN_CHAT,
        CLOSE_CHAT,
        CLEAR_CHAT,
        OPEN_SETTINGS,
        OPEN_PHONE_SETTINGS,
        OPEN_MEMORY,
        OPEN_NOTES,
        OPEN_MULTIBRAIN,
        CYCLE_ORB,
        MUTE_MIC,
        UNMUTE_MIC,
        BACK,
        HOME,
        BLUETOOTH_ON,
        BLUETOOTH_OFF,
        WIFI_ON,
        WIFI_OFF
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
    private val phoneWords = setOf(
        "phone", "system", "android", "device", "mobile", "फोन", "सिस्टम", "एंड्रॉइड"
    )
    private val noteWords = setOf(
        "note", "notes", "notepad", "reminder", "reminders",
        "नोट", "नोट्स", "नोटपैड", "रिमाइंडर"
    )
    private val orbWords = setOf("orb", "theme", "style", "look", "ऑर्ब", "थीम")
    private val bluetoothWords = setOf("bluetooth", "bt", "ब्लूटूथ")
    private val wifiWords = setOf("wifi", "wi-fi", "वाईफाई", "वाई-फाई")
    private val switchOnWords = setOf("on", "enable", "chalu", "start", "ऑन", "चालू", "शुरू")
    private val switchOffWords = setOf("off", "disable", "band", "stop", "बंद")

    fun parse(raw: String): Action? {
        val cleaned = raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s-]"), " ")
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
        val isBluetooth = any(bluetoothWords) || cleaned.contains("blue tooth")
        val isWifi = any(wifiWords) || cleaned.contains("wi fi")
        val isHome = words.contains("home") || words.contains("homescreen") ||
            cleaned.contains("home screen") || words.contains("ghar") ||
            words.contains("घर") || words.contains("होम")
        val isMultiBrain = cleaned.contains("multi brain") ||
            words.contains("multibrain") ||
            (words.contains("brain") && (words.contains("manager") || words.contains("managers")))

        if (words.contains("unmute")) return Action.UNMUTE_MIC
        if (words.contains("mute") || words.contains("muted")) return Action.MUTE_MIC

        if (isHome) return Action.HOME

        if (isBluetooth) {
            if (any(switchOffWords)) return Action.BLUETOOTH_OFF
            if (any(switchOnWords)) return Action.BLUETOOTH_ON
            return null
        }
        if (isWifi) {
            if (any(switchOffWords)) return Action.WIFI_OFF
            if (any(switchOnWords)) return Action.WIFI_ON
            return null
        }

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
            if (wantsClose) return Action.BACK
            if (wantsOpen) {
                return if (any(phoneWords)) Action.OPEN_PHONE_SETTINGS else Action.OPEN_SETTINGS
            }
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
