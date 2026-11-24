package com.gfs.iyango.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionComparatorTest {

    @Test
    fun `compares version codes first when both positive`() {
        assertTrue(AppVersionComparator.isNewer(4, "0.1.2", 3, "0.1.3"))
        assertFalse(AppVersionComparator.isNewer(3, "9.9.9", 3, "0.1.2"))
        assertFalse(AppVersionComparator.isNewer(2, "0.2.0", 3, "0.1.2"))
    }

    @Test
    fun `falls back to semantic version comparison when versionCode unavailable`() {
        assertTrue(AppVersionComparator.isNewer(0, "v0.1.3", 0, "0.1.2-debug"))
        assertTrue(AppVersionComparator.isNewer(0, "0.2.0", 0, "0.1.9"))
        assertTrue(AppVersionComparator.isNewer(0, "1.0.0.1", 0, "1.0.0"))
        assertFalse(AppVersionComparator.isNewer(0, "v0.1.2", 0, "0.1.2"))
        assertFalse(AppVersionComparator.isNewer(0, "0.1.1", 0, "0.1.2"))
    }

    @Test
    fun `evaluates force update flag and minimum supported versionCode`() {
        assertTrue(AppVersionComparator.isForceRequired(true, 0, 3))
        assertTrue(AppVersionComparator.isForceRequired(false, 4, 3))
        assertFalse(AppVersionComparator.isForceRequired(false, 3, 3))
        assertFalse(AppVersionComparator.isForceRequired(false, 0, 3))
    }
}
