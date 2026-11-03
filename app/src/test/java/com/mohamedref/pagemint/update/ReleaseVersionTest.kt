package com.mohamedref.pagemint.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseVersionTest {
    @Test fun semverMapsToMonotonicAndroidVersionCode() {
        assertEquals(1001L, ReleaseVersion.parse("v0.1.1")?.code)
        assertEquals(1002L, ReleaseVersion.parse("v0.1.2")?.code)
        assertEquals(100_003L, ReleaseVersion.parse("v0.100.3")?.code)
        assertEquals(1_002_003L, ReleaseVersion.parse("v1.2.3")?.code)
        assertEquals("1.2.3", ReleaseVersion.parse("v1.2.3")?.name)
    }

    @Test fun invalidAndAmbiguousTagsAreRejected() {
        listOf(
            "0.1.1", "v0.01.1", "v1.2.000", "v1.1000.1", "v1.2.1000",
            "v1.2.3-beta", "v1.2", "v0.0.0", "v99999.1.1", "",
        ).forEach { tag ->
            assertNull("Expected $tag to be rejected", ReleaseVersion.parse(tag))
        }
    }

    @Test fun versionsCanBeComparedWithCurrentlyInstalledPackage() {
        val current = 1L
        val release = ReleaseVersion.parse("v0.1.1")!!
        assert(release.code > current)
        assert(ReleaseVersion.parse("v0.1.2")!!.code > release.code)
    }
}
