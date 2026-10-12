package com.macroresearch.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

const val UPDATE_REPOSITORY = "Reiticia/macro-research"
const val RELEASES_PAGE = "https://github.com/$UPDATE_REPOSITORY/releases"
const val MAX_APK_BYTES = 512L * 1024 * 1024
const val DAY_MILLIS = 24L * 60 * 60 * 1000

data class NoteGroups(val client: List<String>, val backend: List<String>)
data class ReleaseNotes(val versionName: String, val versionCode: Long, val notes: Map<String, NoteGroups>, val schemaVersion: Int = 1) {
    fun localized(tag: String): NoteGroups = notes[when {
        tag.startsWith("zh", true) && (tag.contains("TW", true) || tag.contains("Hant", true) || tag.contains("HK", true)) -> "zh-TW"
        tag.startsWith("zh", true) -> "zh-CN"
        else -> "en"
    }] ?: notes.getValue("en")
}
data class UpdateManifest(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkAssetName: String,
    val apkSize: Long,
    val apkSha256: String,
    val releaseNotes: ReleaseNotes,
)
data class UpdateRelease(val manifest: UpdateManifest, val apkUrl: String, val pageUrl: String)
data class ReleaseAsset(val name: String, val size: Long, val url: String)

enum class UpdateError { NETWORK, RATE_LIMIT, INVALID_RELEASE, UNSUPPORTED_ANDROID, DOWNLOAD, STORAGE, MISSING_FILE, VERIFICATION, SIGNATURE, INSTALL, STATE_STORAGE }
class UpdateFailure(val reason: UpdateError, val retryAt: Long = 0) : Exception(reason.name)
enum class CheckPhase { IDLE, CHECKING, CURRENT, AVAILABLE, NO_RELEASE, FAILED }
data class CheckState(val phase: CheckPhase = CheckPhase.IDLE, val release: UpdateRelease? = null, val error: UpdateError? = null, val retryAt: Long = 0)
enum class DownloadPhase { IDLE, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED }
data class DownloadState(val phase: DownloadPhase = DownloadPhase.IDLE, val bytes: Long = 0, val total: Long = 0, val error: UpdateError? = null, val systemReason: Int = 0)

object UpdateContract {
    private val version = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    private val digest = Regex("[0-9a-f]{64}")
    fun versionParts(value: String): List<Long> {
        require(version.matches(value))
        return value.split('.').map { it.toLong() }
    }
    fun compareVersions(a: String, b: String): Int {
        val left = versionParts(a); val right = versionParts(b)
        return left.zip(right).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
    }
    fun parseNotes(root: JsonObject): ReleaseNotes {
        require(root.long("schemaVersion") == 1L)
        val name = root.text("versionName"); versionParts(name)
        val code = root.long("versionCode"); require(code > 0)
        val entries = root.getAsJsonObject("notes") ?: error("Missing notes")
        require(entries.keySet() == setOf("en", "zh-CN", "zh-TW"))
        val notes = entries.entrySet().associate { (key, value) ->
            val group = value.asJsonObject
            require(group.keySet() == setOf("client", "backend"))
            fun items(field: String): List<String> {
                val values = group.getAsJsonArray(field).map { item ->
                    require(item.isJsonPrimitive && item.asJsonPrimitive.isString)
                    item.asString.also { require(it.isNotBlank() && it.length <= 1000) }
                }
                require(values.size <= 30 && (field != "client" || values.isNotEmpty()))
                return values
            }
            key to NoteGroups(items("client"), items("backend"))
        }
        return ReleaseNotes(name, code, notes)
    }
    fun parseManifest(root: JsonObject): UpdateManifest {
        require(root.long("schemaVersion") == 1L)
        val notes = parseNotes(root.getAsJsonObject("releaseNotes"))
        val result = UpdateManifest(
            packageName = root.text("packageName"), versionCode = root.long("versionCode"),
            versionName = root.text("versionName"), minSdk = root.long("minSdk").toInt(),
            apkAssetName = root.text("apkAssetName"), apkSize = root.long("apkSize"),
            apkSha256 = root.text("apkSha256"), releaseNotes = notes,
        )
        require(result.packageName == "com.macroresearch" && result.versionCode > 0)
        versionParts(result.versionName)
        require(root.long("minSdk") in 26..Int.MAX_VALUE.toLong())
        require(result.apkAssetName == "macro-research-${result.versionName}.apk")
        require(result.apkSize in 1..MAX_APK_BYTES && digest.matches(result.apkSha256))
        require(notes.versionName == result.versionName && notes.versionCode == result.versionCode)
        return result
    }
    fun requiredAssets(name: String) = setOf(
        "macro-research-$name.apk", "market-event-analyzer-$name-x86_64-unknown-linux-gnu.tar.gz",
        "market-event-analyzer-$name-x86_64-unknown-linux-gnu.tar.gz.sha256",
        "release-notes.json", "update.json", "release.json", "SHA256SUMS.txt",
    )
    fun validateProvenance(root: JsonObject, name: String, assets: Map<String, ReleaseAsset>): Map<String, String> {
        require(root.long("schemaVersion") == 1L && root.text("versionName") == name)
        require(root.text("gitCommit").matches(Regex("[0-9a-f]{40}")))
        val expected = requiredAssets(name) - setOf("release.json", "SHA256SUMS.txt")
        val rows = root.getAsJsonArray("assets").map { it.asJsonObject }
        require(rows.size == expected.size)
        val hashes = rows.associate { row ->
            val filename = row.text("name")
            require(filename in expected && row.long("size") == assets.getValue(filename).size)
            filename to row.text("sha256").also { require(digest.matches(it)) }
        }
        require(hashes.keys == expected)
        return hashes
    }
    fun json(bytes: ByteArray) = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun JsonObject.text(field: String): String {
    val value = get(field); require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
    return value.asString
}
internal fun JsonObject.long(field: String): Long {
    val value = get(field); require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
    return value.asBigDecimal.longValueExact()
}
