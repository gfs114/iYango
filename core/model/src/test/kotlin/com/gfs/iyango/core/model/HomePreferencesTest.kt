package com.gfs.iyango.core.model

import org.junit.Assert.*
import org.junit.Test

class HomePreferencesTest {
    @Test fun `future versions safely fall back to supported homepage defaults`() {
        assertEquals(HomePreferences(), HomePreferences(quickActions = emptyList(), version = 99).sanitized())
    }
    @Test fun `normalization drops unknown duplicates and appends missing modules`() {
        val normalized = HomePreferences(moduleOrder = listOf("calendar", "unknown", "calendar"),
            hiddenModules = setOf("profile", "unknown"), quickActions = listOf("grades", "unknown", "grades")).sanitized()
        assertEquals("calendar", normalized.moduleOrder.first())
        assertEquals(HomeModule.entries.size, normalized.moduleOrder.size)
        assertEquals(setOf("profile"), normalized.hiddenModules)
        assertEquals(listOf("grades"), normalized.quickActions)
    }
    @Test fun `all hidden and no shortcuts are intentional choices`() {
        val home = HomePreferences(hiddenModules = HomeModule.entries.map { it.id }.toSet(), quickActions = emptyList())
        assertEquals(home, home.sanitized())
        assertEquals(home, AppPreferences(home = home).sanitized().home)
    }
    @Test fun `shortcut selection keeps at most eight stable registered ids`() {
        assertEquals(8, HomePreferences(quickActions = HomeShortcut.entries.map { it.id }).sanitized().quickActions.size)
        assertEquals(listOf("grades", "import_export", "courses", "semester"), HomePreferences().quickActions)
    }
}
