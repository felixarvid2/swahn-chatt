package se.swahnchat.app.data

/** The colour the app is drawn in. [SYSTEM] follows the wallpaper colours on Android 12+ (and the teal palette below that). */
enum class Accent { SYSTEM, TEAL, BLUE, PURPLE, ROSE, ORANGE, GREEN }

/** What is drawn behind the messages of a chat: nothing, or one of four quiet patterns made in Compose (no pictures). */
enum class ChatBackground { PLAIN, TINT, DOTS, GRID, DIAGONAL }

/** The language of the app: the phone's, or one of the two we translate into. [tag] is the BCP 47 tag, empty = the phone's. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""), SWEDISH("sv"), ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it != SYSTEM && tag != null && (tag == it.tag || tag.startsWith(it.tag + "-")) } ?: SYSTEM
    }
}
