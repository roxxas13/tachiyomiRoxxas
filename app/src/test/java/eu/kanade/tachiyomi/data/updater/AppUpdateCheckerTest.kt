package eu.kanade.tachiyomi.data.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateCheckerTest {
    private val checker = AppUpdateChecker()

    @Test
    fun `uses fork update repository`() {
        assertTrue(APP_UPDATE_REPOSITORY == "roxxas13/tachiyomiRoxxas")
    }

    @Test
    fun `newer stable version is detected`() {
        assertTrue(checker.isNewVersion("v1.8.2", "1.8.1"))
        assertFalse(checker.isNewVersion("v1.8.1", "1.8.1"))
    }

    @Test
    fun `stable supersedes beta of same version`() {
        assertTrue(checker.isNewVersion("v1.8.2", "1.8.2-b01"))
        assertFalse(checker.isNewVersion("v1.8.2-b02", "1.8.2"))
    }

    @Test
    fun `higher beta number is detected`() {
        assertTrue(checker.isNewVersion("v1.8.2-b02", "1.8.2-b01"))
        assertFalse(checker.isNewVersion("v1.8.2-b01", "1.8.2-b02"))
    }
}
