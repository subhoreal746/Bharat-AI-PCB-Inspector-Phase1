package com.example.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.data.local.preferences.SettingsRepositoryImpl
import com.example.domain.model.InspectionPolicyConfig
import com.example.ui.theme.AppThemeMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `theme mode and screening policy config persist and reset cleanly`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempFolder.root, "test_settings.preferences_pb") }
        )
        val settings = SettingsRepositoryImpl(dataStore)

        assertEquals(AppThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(InspectionPolicyConfig(), settings.policyConfig.first())

        settings.setThemeMode(AppThemeMode.DARK)
        assertEquals(AppThemeMode.DARK, settings.themeMode.first())

        val updatedPolicy = InspectionPolicyConfig(
            profileName = "Custom Bench Profile",
            rejectOnCritical = false,
            reviewOnMedium = false,
            minImageWidth = 512,
            minImageHeight = 512
        )
        settings.updatePolicyConfig(updatedPolicy)
        val readBack = settings.policyConfig.first()
        assertEquals("Custom Bench Profile", readBack.profileName)
        assertFalse(readBack.rejectOnCritical)
        assertEquals(512, readBack.minImageWidth)

        settings.resetPolicyDefaults()
        assertEquals(InspectionPolicyConfig(), settings.policyConfig.first())
        assertEquals(224, settings.policyConfig.first().minImageWidth)
        assertEquals(224, settings.policyConfig.first().minImageHeight)
    }

    @Test
    fun `legacy uncustomized 256x256 settings automatically migrate to 224x224 default`() = runTest {
        val file = File(tempFolder.root, "legacy_default_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        // Simulate a pre-existing datastore with 256x256 saved without the explicit customization flag
        dataStore.edit { prefs ->
            prefs[intPreferencesKey("min_image_width")] = 256
            prefs[intPreferencesKey("min_image_height")] = 256
        }

        val settings = SettingsRepositoryImpl(dataStore)
        val policy = settings.policyConfig.first()

        assertEquals(224, policy.minImageWidth)
        assertEquals(224, policy.minImageHeight)
    }

    @Test
    fun `legacy user-customized resolution settings like 320x320 are strictly preserved`() = runTest {
        val file = File(tempFolder.root, "custom_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        // Simulate a pre-existing datastore where user explicitly picked 320x320
        dataStore.edit { prefs ->
            prefs[intPreferencesKey("min_image_width")] = 320
            prefs[intPreferencesKey("min_image_height")] = 320
        }

        val settings = SettingsRepositoryImpl(dataStore)
        val policy = settings.policyConfig.first()

        assertEquals(320, policy.minImageWidth)
        assertEquals(320, policy.minImageHeight)
    }

    @Test
    fun `explicitly choosing 256x256 in updated policy config is respected`() = runTest {
        val file = File(tempFolder.root, "explicit_256_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        val settings = SettingsRepositoryImpl(dataStore)
        settings.updatePolicyConfig(InspectionPolicyConfig(minImageWidth = 256, minImageHeight = 256))

        val policy = settings.policyConfig.first()
        assertEquals(256, policy.minImageWidth)
        assertEquals(256, policy.minImageHeight)
    }
}
