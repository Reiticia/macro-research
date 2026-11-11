package com.macroresearch.data

import com.macroresearch.data.remote.MAX_LOGO_BYTES
import com.macroresearch.data.remote.validCompanyLogo
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

internal data class CachedCompanyLogo(val image: ByteArray?, val fetchedAt: Instant, val refreshAfter: Instant)

/** Rebuildable, bounded cache. Hash-only names; no address, Key or device identity is stored. */
class CompanyLogoDiskCache(
    private val directory: File,
    private val maxEntries: Int = 256,
    private val maxBytes: Long = 32L * 1024 * 1024,
) {
    init { require(maxEntries > 0 && maxBytes > 0) }

    @Synchronized
    internal fun read(key: String): CachedCompanyLogo? {
        val target = file(key)
        if (!target.isFile) return null
        return runCatching {
            require(target.length() in HEADER_BYTES.toLong()..(MAX_LOGO_BYTES + HEADER_BYTES).toLong())
            DataInputStream(target.inputStream()).use { input ->
                require(input.readInt() == MAGIC)
                val fetched = Instant.ofEpochMilli(input.readLong())
                val refresh = Instant.ofEpochMilli(input.readLong())
                val size = input.readInt()
                require(size in 0..MAX_LOGO_BYTES && target.length() == HEADER_BYTES + size.toLong())
                val digest = ByteArray(32).also(input::readFully)
                val image = ByteArray(size).also(input::readFully)
                require(input.read() == -1)
                require(MessageDigest.isEqual(digest, sha256(image)))
                require(size == 0 || validCompanyLogo(image))
                target.setLastModified(System.currentTimeMillis())
                CachedCompanyLogo(image.takeIf { it.isNotEmpty() }, fetched, refresh)
            }
        }.getOrElse { target.delete(); null }
    }

    @Synchronized
    internal fun write(key: String, entry: CachedCompanyLogo) {
        // Cache IO is optional: full disks or a cleared cache must not fail calendar rendering.
        runCatching {
            val image = entry.image ?: byteArrayOf()
            require(image.isEmpty() || validCompanyLogo(image))
            check(directory.isDirectory || directory.mkdirs())
            val target = file(key)
            val bytes = ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use {
                    it.writeInt(MAGIC)
                    it.writeLong(entry.fetchedAt.toEpochMilli())
                    it.writeLong(entry.refreshAfter.toEpochMilli())
                    it.writeInt(image.size)
                    it.write(sha256(image))
                    it.write(image)
                }
            }.toByteArray()
            val temp = File.createTempFile("logo-", ".tmp", directory)
            try {
                FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            prune()
        }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.extension == "logo" }?.sortedBy { it.lastModified() }.orEmpty()
        var count = files.size
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (count <= maxEntries && bytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) { count--; bytes -= size }
        }
    }

    private fun file(key: String): File = File(directory, sha256(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".logo")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val MAGIC = 0x4c4f4701 // LOG + format version 1
        const val HEADER_BYTES = 4 + 8 + 8 + 4 + 32
    }
}
