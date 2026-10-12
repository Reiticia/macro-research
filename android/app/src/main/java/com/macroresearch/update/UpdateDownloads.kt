package com.macroresearch.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

interface UpdateDownloader {
    suspend fun start(release: UpdateRelease): Long
    suspend fun query(ticket: DownloadTicket): DownloadState
    suspend fun verify(ticket: DownloadTicket): File
    suspend fun readyFile(ticket: DownloadTicket): File
    suspend fun remove(ticket: DownloadTicket)
    suspend fun cleanupInstalled()
}

class AndroidUpdateDownloader(private val context: Context, private val installedCode: Long) : UpdateDownloader {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val verifiedDir = File(context.cacheDir, "updates")
    private fun externalDir(): File = context.getExternalFilesDir("updates") ?: throw UpdateFailure(UpdateError.STORAGE)
    private fun downloadFile(ticket: DownloadTicket) = File(externalDir(), ticket.release.manifest.apkAssetName)
    private fun internalFile(ticket: DownloadTicket) = File(verifiedDir, "${ticket.release.manifest.versionCode}.apk")

    override suspend fun start(release: UpdateRelease): Long = withContext(Dispatchers.IO) {
        val manifest = release.manifest
        require(GitHubUrls.asset(release.apkUrl, manifest.versionName, manifest.apkAssetName))
        if (manifest.apkSize !in 1..MAX_APK_BYTES) throw UpdateFailure(UpdateError.VERIFICATION)
        val directory = externalDir()
        directory.mkdirs()
        verifiedDir.mkdirs()
        if (StatFs(directory.path).availableBytes < manifest.apkSize + 1024 * 1024 ||
            StatFs(verifiedDir.path).availableBytes < manifest.apkSize + 1024 * 1024) throw UpdateFailure(UpdateError.STORAGE)
        val destination = File(directory, manifest.apkAssetName)
        if (destination.exists() && !destination.delete()) throw UpdateFailure(UpdateError.STORAGE)
        manager.enqueue(DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Macro Research ${manifest.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverRoaming(false)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "updates", manifest.apkAssetName))
    }

    override suspend fun query(ticket: DownloadTicket): DownloadState = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(ticket.id)).use { cursor ->
            if (!cursor.moveToFirst()) throw UpdateFailure(UpdateError.MISSING_FILE)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            if (bytes > ticket.release.manifest.apkSize || total > ticket.release.manifest.apkSize) throw UpdateFailure(UpdateError.VERIFICATION)
            when (status) {
                DownloadManager.STATUS_FAILED -> DownloadState(DownloadPhase.FAILED, error = if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) UpdateError.STORAGE else UpdateError.DOWNLOAD, systemReason = reason)
                DownloadManager.STATUS_PAUSED -> DownloadState(DownloadPhase.PAUSED, bytes, total, systemReason = reason)
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState(DownloadPhase.VERIFYING, bytes, total)
                else -> DownloadState(DownloadPhase.DOWNLOADING, bytes, total)
            }
        }
    }

    override suspend fun verify(ticket: DownloadTicket): File = withContext(Dispatchers.IO) {
        val source = downloadFile(ticket)
        if (!source.isFile) throw UpdateFailure(UpdateError.MISSING_FILE)
        if (source.length() != ticket.release.manifest.apkSize) throw UpdateFailure(UpdateError.VERIFICATION)
        verifiedDir.mkdirs()
        val temporary = File(verifiedDir, "${ticket.release.manifest.versionCode}.part.apk")
        try {
            source.inputStream().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    copied += count
                    if (copied > ticket.release.manifest.apkSize) throw UpdateFailure(UpdateError.VERIFICATION)
                    output.write(buffer, 0, count)
                }
            } }
            validate(temporary, ticket.release.manifest)
            val destination = internalFile(ticket)
            if (destination.exists() && !destination.delete()) throw UpdateFailure(UpdateError.STORAGE)
            if (!temporary.setReadOnly() || !temporary.renameTo(destination)) throw UpdateFailure(UpdateError.STORAGE)
            destination
        } finally { temporary.delete() }
    }

    override suspend fun readyFile(ticket: DownloadTicket): File = withContext(Dispatchers.IO) {
        val file = internalFile(ticket)
        if (!file.isFile) throw UpdateFailure(UpdateError.MISSING_FILE)
        validate(file, ticket.release.manifest)
        file
    }

    @Suppress("DEPRECATION")
    private suspend fun validate(file: File, expected: UpdateManifest) {
        if (file.length() != expected.apkSize) throw UpdateFailure(UpdateError.VERIFICATION)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != expected.apkSha256) throw UpdateFailure(UpdateError.VERIFICATION)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: throw UpdateFailure(UpdateError.VERIFICATION)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        fun certificates(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { UpdateContract.sha256(it.toByteArray()) }?.toSet().orEmpty()
        validateApkIdentity(
            ApkIdentity(archive.packageName, code, archive.versionName, archive.applicationInfo?.minSdkVersion, certificates(archive)),
            ApkIdentity(current.packageName, installedCode, current.versionName, current.applicationInfo?.minSdkVersion, certificates(current)),
            expected, Build.VERSION.SDK_INT,
        )
    }

    override suspend fun remove(ticket: DownloadTicket) = withContext(Dispatchers.IO) {
        runCatching { manager.remove(ticket.id) }
        runCatching { downloadFile(ticket).delete() }
        runCatching { internalFile(ticket).delete() }
        runCatching { File(verifiedDir, "${ticket.release.manifest.versionCode}.part.apk").delete() }
        Unit
    }
    override suspend fun cleanupInstalled() = withContext(Dispatchers.IO) {
        verifiedDir.listFiles()?.forEach { it.delete() }
        context.getExternalFilesDir("updates")?.listFiles()?.forEach { it.delete() }
        Unit
    }
}
