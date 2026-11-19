package dev.offreel.data

/** A cleaned-up Instagram link: tracking parameters dropped, one canonical form per post. */
data class InstagramLink(val kind: String, val shortcode: String) {
    val url: String get() = "https://www.instagram.com/$kind/$shortcode/"
}

object LinkParser {
    private val pattern = Regex(
        """https?://(?:www\.|m\.)?instagram\.com/(?:[A-Za-z0-9._]+/)?(reels?|p|tv)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Finds the first Instagram reel/post link in [text] (shared text can contain more than a URL). */
    fun parse(text: String?): InstagramLink? {
        val m = pattern.find(text ?: return null) ?: return null
        val kind = when (m.groupValues[1].lowercase()) {
            "p" -> "p"
            else -> "reel"   // reel, reels, tv
        }
        return InstagramLink(kind, m.groupValues[2])
    }
}
