package me.capcom.smsgateway.modules.fish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishUpdateCheckerTest {
    @Test
    fun parseVersion_acceptsTagsAndPlainVersions() {
        assertEquals(listOf(2, 0, 1), FishUpdateChecker.parseVersion("v2.0.1"))
        assertEquals(listOf(10, 2, 3), FishUpdateChecker.parseVersion("10.2.3"))
        assertNull(FishUpdateChecker.parseVersion("v2.0"))
        assertNull(FishUpdateChecker.parseVersion("v2.0.1-beta"))
        assertNull(FishUpdateChecker.parseVersion(""))
    }

    @Test
    fun isNewer_comparesNumerically() {
        assertTrue(FishUpdateChecker.isNewer("v2.0.2", "2.0.1"))
        assertTrue(FishUpdateChecker.isNewer("2.10.0", "2.9.9"))
        assertTrue(FishUpdateChecker.isNewer("3.0.0", "2.99.99"))
        assertFalse(FishUpdateChecker.isNewer("2.0.1", "2.0.1"))
        assertFalse(FishUpdateChecker.isNewer("2.0.0", "2.0.1"))
        assertFalse(FishUpdateChecker.isNewer("garbage", "2.0.1"))
    }
}
