package com.macroresearch.ui.common

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.macroresearch.BuildConfig
import com.macroresearch.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdatePlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualArchiveAndSignatureAreVerifiedAndTamperingBlocksInstallation() = runBlocking {
        // Reuse only the public, installed TEST APK. Do not touch release keys or enqueue a network download.
        val apk = File(context.applicationInfo.sourceDir)
        val notes = context.assets.open("release-notes.json").use { UpdateContract.parseNotes(UpdateContract.json(it.readBytes())) }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        }
        val manifest = UpdateManifest(packageName = context.packageName, versionCode = BuildConfig.VERSION_CODE.toLong(),
            versionName = BuildConfig.VERSION_NAME, minSdk = 26, apkAssetName = "macro-research-${BuildConfig.VERSION_NAME}.apk",
            apkSize = apk.length(), apkSha256 = hash.digest().joinToString("") { "%02x".format(it) }, releaseNotes = notes)
        val target = UpdateRelease(manifest, "https://github.com/$UPDATE_REPOSITORY/releases/download/v${manifest.versionName}/${manifest.apkAssetName}", RELEASES_PAGE)
        val external = File(context.getExternalFilesDir("updates"), manifest.apkAssetName)
        external.parentFile!!.mkdirs()
        apk.copyTo(external, overwrite = true)
        // Model the preceding version while using the same test signing identity.
        val verifier = AndroidUpdateDownloader(context, manifest.versionCode - 1)
        val ticket = DownloadTicket(Long.MAX_VALUE, target)
        try {
            val verified = verifier.verify(ticket)
            assertTrue(verified.isFile)
            assertEquals(verified, verifier.readyFile(ticket))
            java.io.RandomAccessFile(external, "rw").use { it.seek(0); it.write(0) }
            try { verifier.verify(ticket); fail("Tampered APK accepted") }
            catch (failure: UpdateFailure) { assertEquals(UpdateError.VERIFICATION, failure.reason) }
        } finally {
            external.delete()
            File(context.cacheDir, "updates/${manifest.versionCode}.apk").delete()
        }
    }
    @Suppress("DEPRECATION")
    @Test fun installerPermissionIsDeclaredAndProviderIsPrivate() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions?.contains("android.permission.REQUEST_INSTALL_PACKAGES") == true)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.update-files", 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertTrue(Build.VERSION.SDK_INT >= 26)
    }
}
