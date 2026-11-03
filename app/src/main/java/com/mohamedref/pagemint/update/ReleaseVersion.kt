package com.mohamedref.pagemint.update

/** Public GitHub Releases tags must follow vMAJOR.MINOR.PATCH, where MINOR/PATCH < 1000.
 * The tag determines the Android versionCode without relying on publication dates. */
object ReleaseVersion {
    private val tagRegex = Regex("""^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$""")

    fun parse(tag: String): Version? {
        val parts = tagRegex.matchEntire(tag) ?: return null
        val major = parts.groupValues[1].toLongOrNull() ?: return null
        val minor = parts.groupValues[2].toLongOrNull() ?: return null
        val patch = parts.groupValues[3].toLongOrNull() ?: return null
        if (major > 2000 || minor > 999 || patch > 999) return null
        val number = major * 1_000_000L + minor * 1_000L + patch
        if (number !in 1L..2_100_000_000L) return null
        return Version("$major.$minor.$patch", number)
    }

    data class Version(val name: String, val code: Long)
}
