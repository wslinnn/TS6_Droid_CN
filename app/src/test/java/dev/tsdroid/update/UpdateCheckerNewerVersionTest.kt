package dev.tsdroid.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerNewerVersionTest {

    @Test
    fun `higher patch version is newer`() {
        assertTrue(UpdateChecker.isNewerVersion("2.1.4", "2.1.5"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(UpdateChecker.isNewerVersion("2.1.4", "2.1.4"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(UpdateChecker.isNewerVersion("2.1.4", "2.1.3"))
    }

    @Test
    fun `numeric comparison is not lexicographic`() {
        assertTrue(UpdateChecker.isNewerVersion("2.1.9", "2.1.10"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertTrue(UpdateChecker.isNewerVersion("2.1", "2.1.1"))
        assertFalse(UpdateChecker.isNewerVersion("2.1.1", "2.1"))
    }

    @Test
    fun `non numeric suffixes are ignored`() {
        assertTrue(UpdateChecker.isNewerVersion("2.1.4-beta", "2.1.5"))
        assertFalse(UpdateChecker.isNewerVersion("2.1.4", "2.1.4-rc1"))
    }
}
