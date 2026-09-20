package com.macroresearch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend address is a credential-bearing endpoint, so the validation rules are part of the
 * contract: no embedded credentials, no path, and explicit opt-in for plain HTTP.
 */
class BackendPreferencesTest {
    @Test
    fun acceptsHttpsAddressesAndTrimsTrailingSlashes() {
        assertEquals(
            "https://macro.example.com",
            BackendPreferences.normalizeBaseUrl("https://macro.example.com/"),
        )
        assertEquals(
            "https://macro.example.com:8443",
            BackendPreferences.normalizeBaseUrl("  https://macro.example.com:8443  "),
        )
        assertEquals(
            "https://159.75.202.65",
            BackendPreferences.normalizeBaseUrl("https://159.75.202.65"),
        )
    }

    @Test
    fun rejectsPlainHttpUnlessExplicitlyAllowed() {
        // Default: no cleartext at all.
        assertThrows(IllegalArgumentException::class.java) {
            BackendPreferences.normalizeBaseUrl("http://192.168.1.10:8080")
        }
        // Once explicitly enabled, both private and public endpoints are supported.
        assertEquals(
            "http://192.168.1.10:8080",
            BackendPreferences.normalizeBaseUrl("http://192.168.1.10:8080", allowsCleartext = true),
        )
        assertEquals(
            "http://100.64.0.7:8080",
            BackendPreferences.normalizeBaseUrl("http://100.64.0.7:8080", allowsCleartext = true),
        )
        assertEquals(
            "http://localhost:8080",
            BackendPreferences.normalizeBaseUrl("http://localhost:8080", allowsCleartext = true),
        )
        // The emulator's alias for the host loopback lives inside 10/8, so a development
        // backend running on the developer's machine is reachable without a certificate.
        assertEquals(
            "http://10.0.2.2:8080",
            BackendPreferences.normalizeBaseUrl("http://10.0.2.2:8080", allowsCleartext = true),
        )
        assertEquals(
            "http://203.0.113.10:5090",
            BackendPreferences.normalizeBaseUrl("http://203.0.113.10:5090", allowsCleartext = true),
        )
        assertEquals(
            "http://macro.example.com:5090",
            BackendPreferences.normalizeBaseUrl("http://macro.example.com:5090", allowsCleartext = true),
        )
        assertTrue(BackendPreferences.usesPlainHttp("http://192.168.1.10:8080"))
        assertTrue(!BackendPreferences.usesPlainHttp("https://macro.example.com"))
    }

    @Test
    fun rejectsPlainHttpAndEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendPreferences.normalizeBaseUrl("https://user:pass@macro.example.com")
        }
    }

    @Test
    fun rejectsPathsQueriesAndFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendPreferences.normalizeBaseUrl("https://macro.example.com/api")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendPreferences.normalizeBaseUrl("https://macro.example.com?token=x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendPreferences.normalizeBaseUrl("https://macro.example.com#frag")
        }
    }

    @Test
    fun rejectsEmptyAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendPreferences.normalizeBaseUrl("   ")
        }
    }

    @Test
    fun modeNamesRoundTripAndFallBackToDirect() {
        assertEquals(DataSourceMode.BACKEND, DataSourceMode.fromName("backend"))
        assertEquals(DataSourceMode.BACKEND, DataSourceMode.fromName("BACKEND"))
        assertEquals(DataSourceMode.DIRECT, DataSourceMode.fromName("direct"))
        assertEquals(DataSourceMode.DIRECT, DataSourceMode.fromName(null))
        assertEquals(DataSourceMode.DIRECT, DataSourceMode.fromName("garbage"))
        // Direct is the default so an existing install keeps working after an upgrade.
        assertEquals(DataSourceMode.DIRECT, BackendSettings().mode)
        assertTrue(!BackendSettings().configured)
    }
}
