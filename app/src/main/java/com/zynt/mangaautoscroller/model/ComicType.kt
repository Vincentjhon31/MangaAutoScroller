package com.zynt.mangaautoscroller.model

enum class ComicType(val displayName: String, val description: String) {
    MANGA("Manga", "Japanese comics read right-to-left with variable panel layouts"),
    MANHWA("Manhwa", "Korean comics optimized for vertical scrolling"),
    MANHUA("Manhua", "Chinese comics with similar vertical layout to manhwa");

    companion object {
        fun fromString(value: String): ComicType {
            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                MANGA // Default to manga if invalid value
            }
        }
    }
}

enum class ScrollDistance(val displayName: String, val description: String, val screenPercentage: Float) {
    SMALL("Small", "25-40% of screen - best for manga with dense text", 0.3f),
    MEDIUM("Medium", "40-60% of screen - balanced for mixed content", 0.5f),
    LARGE("Large", "60-80% of screen - optimal for manhwa/manhua", 0.7f);

    companion object {
        fun fromString(value: String): ScrollDistance {
            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                MEDIUM // Default to medium if invalid value
            }
        }
    }
}

enum class ReadingSpeed(val displayName: String, val multiplier: Float) {
    SLOW("Slow", 0.7f),
    NORMAL("Normal", 1.0f),
    FAST("Fast", 1.5f);

    companion object {
        fun fromString(value: String): ReadingSpeed {
            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                NORMAL // Default to normal if invalid value
            }
        }
    }
}
