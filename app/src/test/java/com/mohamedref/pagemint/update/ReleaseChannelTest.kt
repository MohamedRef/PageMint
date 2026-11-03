package com.mohamedref.pagemint.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseChannelTest {
    private fun preview(tag: String) = ReleaseChannel.Candidate(tag, draft = false, prerelease = true)
    private fun stable(tag: String) = ReleaseChannel.Candidate(tag, draft = false, prerelease = false)

    @Test fun selectsNewestPublishedPreviewNotNewestStable() {
        val releases = listOf(stable("v2.0.0"), preview("v0.1.2"), preview("v0.1.4"), preview("v0.1.3"))
        assertEquals("v0.1.4", ReleaseChannel.latestPreview(releases)?.tag)
    }

    @Test fun ignoresDraftsAndMalformedTags() {
        val releases = listOf(
            ReleaseChannel.Candidate("v0.9.9", draft = true, prerelease = true),
            preview("v0.1.3-preview"),
            preview("v0.1.2"),
            stable("v1.0.0"),
        )
        assertEquals("v0.1.2", ReleaseChannel.latestPreview(releases)?.tag)
    }

    @Test fun returnsNothingWhenOnlyStableReleasesExist() {
        assertNull(ReleaseChannel.latestPreview(listOf(stable("v1.0.0"), stable("v1.1.0"))))
        assertNull(ReleaseChannel.latestPreview(emptyList()))
    }

    @Test fun releaseOrderDoesNotOverrideVersionOrdering() {
        assertEquals("v1.2.0", ReleaseChannel.latestPreview(listOf(preview("v1.1.9"), preview("v1.2.0"), preview("v0.9.99")))?.tag)
    }
}
