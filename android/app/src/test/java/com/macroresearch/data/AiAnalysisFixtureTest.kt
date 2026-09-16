package com.macroresearch.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.macroresearch.data.remote.AiAnalysisClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model-reply parser exists twice: on the device (direct mode) and on the server
 * (backend mode). Both suites read the same corpus in `src/test/resources/ai-analysis`, so the
 * two implementations cannot drift apart.
 */
class AiAnalysisFixtureTest {
    private val gson = Gson()
    private val client = AiAnalysisClient(OkHttpClient(), gson)

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/ai-analysis/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun bothParsersShareOneCorpus() {
        val cases = gson.fromJson(fixture("manifest.json"), Array<JsonObject>::class.java)
        assertTrue("the fixture manifest must not be empty", cases.isNotEmpty())
        for (case in cases) {
            val name = case.get("name").asString
            val response = fixture(case.get("file").asString)
            val expectedOk = case.get("ok").asBoolean
            val parsed = runCatching { client.parseResponse(response) }
            assertEquals("fixture $name: unexpected outcome $parsed", expectedOk, parsed.isSuccess)
            val draft = parsed.getOrNull() ?: continue
            case.get("dataAnalysis")?.takeUnless { it.isJsonNull }?.let {
                assertEquals("fixture $name: dataAnalysis", it.asString, draft.dataAnalysis)
            }
            case.get("chainLength")?.takeUnless { it.isJsonNull }?.let {
                assertEquals("fixture $name: chain length", it.asInt, draft.chain.size)
            }
        }
    }

    @Test
    fun snakeCaseFieldsAreNormalizedTheSameWayAsTheServerParser() {
        val draft = client.parseResponse(fixture("snake-case.txt"))
        assertEquals("d", draft.dataAnalysis)
        assertEquals("o", draft.marketOutlook)
        assertEquals("k", draft.risks)
        assertEquals("up", draft.chain[0].direction)
        assertEquals("confirmed", draft.chain[0].verdict)
    }
}
