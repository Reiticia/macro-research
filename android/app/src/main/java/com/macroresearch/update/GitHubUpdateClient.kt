package com.macroresearch.update

import com.macroresearch.data.remote.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

fun interface UpdateTransport { suspend fun get(url: String): ByteArray }
fun interface UpdateChecker { suspend fun latest(): UpdateRelease? }

object GitHubUrls {
    private val resources = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun allowed(url: String): Boolean = runCatching {
        val parsed = url.toHttpUrl()
        parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.fragment == null && (parsed.host in resources ||
            (parsed.host == "api.github.com" && parsed.encodedPath.startsWith("/repos/$UPDATE_REPOSITORY/")) ||
            (parsed.host == "github.com" && parsed.encodedPath.startsWith("/$UPDATE_REPOSITORY/releases/")))
    }.getOrDefault(false)
    fun asset(url: String, version: String, name: String): Boolean =
        url == "https://github.com/$UPDATE_REPOSITORY/releases/download/v$version/$name"
}

class GitHubTransport(private val now: () -> Long = System::currentTimeMillis) : UpdateTransport {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (!GitHubUrls.allowed(chain.request().url.toString())) throw java.io.IOException("Unsafe update URL")
            chain.proceed(chain.request())
        }.build()

    override suspend fun get(url: String): ByteArray {
        if (!GitHubUrls.allowed(url)) throw UpdateFailure(UpdateError.INVALID_RELEASE)
        return client.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MacroResearch-updater")
            .cacheControl(CacheControl.Builder().noCache().noStore().build()).build()).awaitResponse(::readResponse)
    }

    internal fun readResponse(response: okhttp3.Response): ByteArray {
        val limited = response.code == 429 || (response.code == 403 &&
            (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null))
        if (limited) {
            val retry = response.header("Retry-After")
            val retryTime = retry?.toLongOrNull()?.let { now() + it.coerceIn(1, 86400) * 1000 }
                ?: runCatching { ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                ?: response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
                ?: (now() + 60_000)
            throw UpdateFailure(UpdateError.RATE_LIMIT, retryTime.coerceAtLeast(now() + 1000))
        }
        if (!response.isSuccessful) throw UpdateFailure(UpdateError.NETWORK)
        val body = response.body ?: throw UpdateFailure(UpdateError.NETWORK)
        if (body.contentLength() > MAX_METADATA) throw UpdateFailure(UpdateError.INVALID_RELEASE)
        val result = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (result.size() + count > MAX_METADATA) throw UpdateFailure(UpdateError.INVALID_RELEASE)
                result.write(buffer, 0, count)
            }
        }
        return result.toByteArray()
    }
    companion object { private const val MAX_METADATA = 1024 * 1024 }
}

class GitHubUpdateClient(private val transport: UpdateTransport) : UpdateChecker {
    override suspend fun latest(): UpdateRelease? = try {
        withTimeout(30_000) { discover() }
    } catch (_: TimeoutCancellationException) {
        throw UpdateFailure(UpdateError.NETWORK)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: UpdateFailure) {
        throw failure
    } catch (_: java.io.IOException) {
        throw UpdateFailure(UpdateError.NETWORK)
    } catch (_: Exception) {
        throw UpdateFailure(UpdateError.INVALID_RELEASE)
    }

    private suspend fun discover(): UpdateRelease? {
        data class Candidate(val version: String, val assets: Map<String, ReleaseAsset>)
        val candidates = mutableListOf<Candidate>()
        for (page in 1..3) {
            val bytes = transport.get("https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=100&page=$page")
            val rows = com.google.gson.JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonArray
            for (row in rows) {
                val root = row.asJsonObject
                if (root.get("draft")?.asBoolean != false || root.get("prerelease")?.asBoolean != false) continue
                val tag = root.text("tag_name")
                if (!tag.startsWith("v")) continue
                val version = tag.drop(1)
                if (runCatching { UpdateContract.versionParts(version) }.isFailure) continue
                val assetRows = root.getAsJsonArray("assets").map { it.asJsonObject }
                val assets = assetRows.associate { asset ->
                    val name = asset.text("name")
                    name to ReleaseAsset(name, asset.long("size"), asset.text("browser_download_url"))
                }
                if (assets.size != assetRows.size || !assets.keys.containsAll(UpdateContract.requiredAssets(version))) continue
                candidates += Candidate(version, assets)
            }
            if (rows.size() < 100) break
        }
        val newest = candidates.maxWithOrNull { a, b -> UpdateContract.compareVersions(a.version, b.version) } ?: return null
        val required = UpdateContract.requiredAssets(newest.version)
        required.forEach { name ->
            val asset = newest.assets.getValue(name)
            require(asset.size in 1..MAX_APK_BYTES && GitHubUrls.asset(asset.url, newest.version, name))
        }
        suspend fun read(name: String): ByteArray = transport.get(newest.assets.getValue(name).url).also {
            require(it.size.toLong() == newest.assets.getValue(name).size)
        }
        val provenanceBytes = read("release.json")
        val hashes = UpdateContract.validateProvenance(UpdateContract.json(provenanceBytes), newest.version, newest.assets)
        val updateBytes = read("update.json")
        val notesBytes = read("release-notes.json")
        require(UpdateContract.sha256(updateBytes) == hashes["update.json"])
        require(UpdateContract.sha256(notesBytes) == hashes["release-notes.json"])
        val sums = read("SHA256SUMS.txt").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { line ->
            val match = Regex("([0-9a-f]{64})  ([A-Za-z0-9._-]+)").matchEntire(line) ?: error("Invalid checksum")
            match.groupValues[2] to match.groupValues[1]
        }.toList()
        require(sums.size == required.size - 1 && sums.map { it.first }.toSet() == required - "SHA256SUMS.txt")
        val checksums = sums.toMap()
        require(checksums["release.json"] == UpdateContract.sha256(provenanceBytes))
        require(hashes.all { (name, hash) -> checksums[name] == hash })
        val manifest = UpdateContract.parseManifest(UpdateContract.json(updateBytes))
        require(manifest.versionName == newest.version)
        require(manifest.releaseNotes == UpdateContract.parseNotes(UpdateContract.json(notesBytes)))
        require(manifest.apkSize == newest.assets.getValue(manifest.apkAssetName).size)
        require(manifest.apkSha256 == hashes[manifest.apkAssetName])
        return UpdateRelease(manifest, newest.assets.getValue(manifest.apkAssetName).url, "$RELEASES_PAGE/tag/v${manifest.versionName}")
    }
}
