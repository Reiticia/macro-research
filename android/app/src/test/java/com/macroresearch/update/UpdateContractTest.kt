package com.macroresearch.update

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal fun notes(code: Long = 17, version: String = "0.15.0") = ReleaseNotes(version, code,
    mapOf("en" to NoteGroups(listOf("English"), emptyList()), "zh-CN" to NoteGroups(listOf("简体"), emptyList()), "zh-TW" to NoteGroups(listOf("繁體"), emptyList())))
internal fun release(code: Long = 17, version: String = "0.15.0"): UpdateRelease {
    val manifest = UpdateManifest(packageName = "com.macroresearch", versionCode = code, versionName = version,
        minSdk = 26, apkAssetName = "macro-research-$version.apk", apkSize = 3, apkSha256 = UpdateContract.sha256(byteArrayOf(1, 2, 3)), releaseNotes = notes(code, version))
    return UpdateRelease(manifest, "https://github.com/$UPDATE_REPOSITORY/releases/download/v$version/${manifest.apkAssetName}", "$RELEASES_PAGE/tag/v$version")
}

class UpdateContractTest {
    private val gson = Gson()
    @Test fun numericVersionsAndNotesAreStrictAndLocalized() {
        assertTrue(UpdateContract.compareVersions("0.10.0", "0.9.9") > 0)
        assertEquals("繁體", notes().localized("zh-Hant-HK").client.single())
        assertEquals("简体", notes().localized("zh-CN").client.single())
        assertEquals("English", notes().localized("fr-FR").client.single())
        assertEquals(notes(), UpdateContract.parseNotes(gson.toJsonTree(notes()).asJsonObject))
        val bad = gson.toJsonTree(notes()).asJsonObject
        bad.getAsJsonObject("notes").remove("zh-TW")
        assertThrows(IllegalArgumentException::class.java) { UpdateContract.parseNotes(bad) }
        for (version in listOf("v1.0.0", "1.2", "1.0.0-beta", "01.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateContract.versionParts(version) }
        }
    }
    @Test fun manifestRejectsIdentityVersionAndUnboundedSizes() {
        val original = gson.toJsonTree(release().manifest).asJsonObject
        assertEquals(release().manifest, UpdateContract.parseManifest(original))
        for ((field, value) in listOf("schemaVersion" to "2", "versionCode" to "17.5", "minSdk" to "4294967322", "apkSize" to "536870913")) {
            val bad = original.deepCopy(); bad.add(field, com.google.gson.JsonParser.parseString(value))
            assertThrows(Exception::class.java) { UpdateContract.parseManifest(bad) }
        }
        val bad = original.deepCopy(); bad.addProperty("packageName", "another.app")
        assertThrows(IllegalArgumentException::class.java) { UpdateContract.parseManifest(bad) }
    }
    @Test fun onlyOfficialHttpsUrlsAreAccepted() {
        assertTrue(GitHubUrls.asset(release().apkUrl, "0.15.0", "macro-research-0.15.0.apk"))
        assertTrue(GitHubUrls.allowed("https://release-assets.githubusercontent.com/asset?signature=test"))
        for (url in listOf("http://github.com/$UPDATE_REPOSITORY/releases/a", "https://github.com/evil/repo/releases/a",
            "https://user:password@github.com/$UPDATE_REPOSITORY/releases/a", "https://evil.example/a", "https://api.github.com:444/repos/$UPDATE_REPOSITORY/releases")) assertFalse(GitHubUrls.allowed(url))
    }
    @Test fun apkIdentityRejectsDowngradeWrongPackageAndSigningChanges() {
        val expected = release().manifest
        val installed = ApkIdentity("com.macroresearch", 16, "0.14.0", 26, setOf("trusted"))
        val archive = installed.copy(versionCode = 17, versionName = "0.15.0")
        validateApkIdentity(archive, installed, expected, 35)
        for (bad in listOf(archive.copy(versionCode = 16), archive.copy(packageName = "evil.app"), archive.copy(minSdk = 27))) {
            assertEquals(UpdateError.VERIFICATION, assertThrows(UpdateFailure::class.java) { validateApkIdentity(bad, installed, expected, 35) }.reason)
        }
        assertEquals(UpdateError.SIGNATURE, assertThrows(UpdateFailure::class.java) { validateApkIdentity(archive.copy(certificates = setOf("debug")), installed, expected, 35) }.reason)
        assertThrows(UpdateFailure::class.java) { validateApkIdentity(archive, installed, expected, 25) }
    }

    private fun complete(version: String, code: Long): Pair<JsonObject, Map<String, ByteArray>> {
        val target = release(code, version)
        val base = "https://github.com/$UPDATE_REPOSITORY/releases/download/v$version/"
        val contents = mutableMapOf<String, ByteArray>()
        contents["update.json"] = gson.toJson(target.manifest).toByteArray()
        contents["release-notes.json"] = gson.toJson(target.manifest.releaseNotes).toByteArray()
        val filename = "market-event-analyzer-$version-x86_64-unknown-linux-gnu.tar.gz"
        contents[filename] = byteArrayOf(4, 5)
        contents["$filename.sha256"] = "test".toByteArray()
        contents[target.manifest.apkAssetName] = byteArrayOf(1, 2, 3)
        val provenance = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("versionName", version); addProperty("gitCommit", "a".repeat(40))
            add("assets", JsonArray().apply { contents.forEach { (name, value) -> add(JsonObject().apply {
                addProperty("name", name); addProperty("size", value.size); addProperty("sha256", UpdateContract.sha256(value))
            }) } })
        }
        contents["release.json"] = gson.toJson(provenance).toByteArray()
        contents["SHA256SUMS.txt"] = contents.entries.joinToString("\n", postfix = "\n") { (name, bytes) -> "${UpdateContract.sha256(bytes)}  $name" }.toByteArray()
        val row = JsonObject().apply {
            addProperty("draft", false); addProperty("prerelease", false); addProperty("tag_name", "v$version")
            add("assets", JsonArray().apply { contents.forEach { (name, value) -> add(JsonObject().apply {
                addProperty("name", name); addProperty("size", value.size); addProperty("browser_download_url", base + name)
            }) } })
        }
        return row to contents.mapKeys { base + it.key }
    }
    @Test fun discoveryFiltersLegacyReleasesAndSelectsHighestNumericVersion() = runBlocking {
        val (older, oldFiles) = complete("0.9.9", 18)
        val (newer, newFiles) = complete("0.10.0", 19)
        val legacy = newer.deepCopy().apply { addProperty("tag_name", "v0.14.0"); add("assets", JsonArray()) }
        val backend = newer.deepCopy().apply { addProperty("tag_name", "backend-v9.0.0") }
        val prerelease = newer.deepCopy().apply { addProperty("prerelease", true); addProperty("tag_name", "v99.0.0") }
        val rows = JsonArray().apply { add(older); add(newer); add(legacy); add(backend); add(prerelease) }
        val client = GitHubUpdateClient { url -> if (url.contains("api.github.com")) gson.toJson(rows).toByteArray() else (newFiles + oldFiles).getValue(url) }
        assertEquals("0.10.0", client.latest()!!.manifest.versionName)
    }
    @Test fun noCompleteReleaseIsNotReportedAsLatestAndCorruptNewestDoesNotFallBack() = runBlocking {
        assertNull(GitHubUpdateClient { "[]".toByteArray() }.latest())
        val (row, files) = complete("0.15.0", 17)
        val rows = JsonArray().apply { add(row) }
        val client = GitHubUpdateClient { url -> if (url.contains("api.github.com")) gson.toJson(rows).toByteArray() else if (url.endsWith("update.json")) ByteArray(files.getValue(url).size) else files.getValue(url) }
        try { client.latest(); fail("Corrupt update accepted") } catch (error: UpdateFailure) { assertEquals(UpdateError.INVALID_RELEASE, error.reason) }
    }
    @Test fun paginationIsBoundedAndIncompleteDraftsAreSkipped() = runBlocking {
        var requests = 0
        val rows = JsonArray().apply { repeat(100) { add(JsonObject().apply { addProperty("draft", true); addProperty("prerelease", false) }) } }
        assertNull(GitHubUpdateClient { requests++; gson.toJson(rows).toByteArray() }.latest())
        assertEquals(3, requests)
    }
}
