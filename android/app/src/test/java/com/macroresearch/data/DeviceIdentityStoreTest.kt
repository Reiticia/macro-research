package com.macroresearch.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeviceIdentityStoreTest {
    @Test fun installationPersistsAcrossStoreRecreationAndAndroidIdIsNotSentRaw() {
        var saved: String? = null
        fun store(android: String) = DeviceIdentityStore({ saved }, { saved = it; true }, { android })
        val first = store("0123456789abcdef").identity()!!
        assertEquals(first.installationId, UUID.fromString(first.installationId).toString())
        assertEquals(64, first.androidIdHash.length)
        assertNotEquals("0123456789abcdef", first.androidIdHash)
        assertEquals(first, store("0123456789abcdef").identity())
        val changedUser = store("fedcba9876543210").identity()!!
        assertEquals(first.installationId, changedUser.installationId)
        assertNotEquals(first.androidIdHash, changedUser.androidIdHash)
        saved = null // Clear app data / new installation. No cross-device backup restoration.
        assertNotEquals(first.installationId, store("0123456789abcdef").identity()!!.installationId)
    }

    @Test fun missingIdOrFailedPersistenceNeverProducesAFakeIdentity() {
        assertNull(DeviceIdentityStore({ null }, { true }, { null }).identity())
        assertNull(DeviceIdentityStore({ null }, { true }, { " " }).identity())
        val failed = runCatching { DeviceIdentityStore({ null }, { false }, { "test-id" }).identity() }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }
}
