package com.mohamedref.pagemint.update

/**
 * Preview-only release policy. GitHub's /releases/latest endpoint excludes
 * Pre-releases, so callers fetch the releases list and select by Android
 * versionCode, not publication date. Stable releases are never selected.
 */
object ReleaseChannel {
    data class Candidate(
        val tag: String,
        val draft: Boolean,
        val prerelease: Boolean,
    )

    fun latestPreview(releases: List<Candidate>): Candidate? =
        releases.asSequence()
            .filter { !it.draft && it.prerelease }
            .mapNotNull { release ->
                val version = ReleaseVersion.parse(release.tag) ?: return@mapNotNull null
                release to version.code
            }
            .maxByOrNull { (_, versionCode) -> versionCode }
            ?.first
}
