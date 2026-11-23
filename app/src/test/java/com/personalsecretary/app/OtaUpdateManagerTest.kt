package com.personalsecretary.app

import com.personalsecretary.app.update.OtaUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaUpdateManagerTest {

    @Test
    fun comparesSemanticVersionsCorrectly() {
        assertTrue(OtaUpdateManager.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(OtaUpdateManager.isNewerVersion("v1.2.0", "1.1.9"))
        assertTrue(OtaUpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(OtaUpdateManager.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(OtaUpdateManager.isNewerVersion("v0.9.9", "1.0.0"))
    }

    @Test
    fun parsesGitHubReleaseJsonAndExtractsSha256() {
        val fakeSha = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val json = """
            {
              "tag_name": "v1.1.0",
              "name": "Personal Secretary v1.1.0",
              "body": "Improved step syncing.\nSHA256: $fakeSha",
              "published_at": "2026-11-23T10:00:00Z",
              "assets": [
                {
                  "name": "PersonalSecretary-v1.1.0.apk",
                  "size": 48123456,
                  "browser_download_url": "https://github.com/Siroj-ai/personal-secretary/releases/download/v1.1.0/PersonalSecretary-v1.1.0.apk",
                  "url": "https://api.github.com/repos/Siroj-ai/personal-secretary/releases/assets/123"
                },
                {
                  "name": "PersonalSecretary-v1.1.0.apk.sha256",
                  "size": 90,
                  "browser_download_url": "https://github.com/Siroj-ai/personal-secretary/releases/download/v1.1.0/PersonalSecretary-v1.1.0.apk.sha256",
                  "url": "https://api.github.com/repos/Siroj-ai/personal-secretary/releases/assets/124"
                }
              ]
            }
        """.trimIndent()

        val release = OtaUpdateManager.parseGitHubReleaseJson(json)
        assertEquals("1.1.0", release.versionName)
        assertEquals("PersonalSecretary-v1.1.0.apk", release.apkName)
        assertEquals(fakeSha, release.expectedSha256)
        assertTrue(OtaUpdateManager.isNewerVersion(release.versionName, "1.0.0"))
    }
}
