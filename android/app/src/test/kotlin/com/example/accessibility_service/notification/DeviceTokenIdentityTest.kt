package com.example.accessibility_service.notification

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class DeviceTokenIdentityTest {
    private val identity = DeviceTokenIdentity { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }

    @Test fun readsDeviceSubjectOnlyForLocalStorageRouting() {
        assertEquals(ID, identity.deviceId(jwt("""{"typ":"device","sub":"$ID"}""")))
        assertEquals(ID, identity.deviceId(jwt("""{"typ":"device","sub":"${ID.uppercase()}"}""")))
    }

    @Test fun rejectsPanelMalformedAndMissingClaimsWithoutThrowing() {
        for (token in listOf("", "a.b", "a.b.c.d", "a.%.c",
            jwt("{}"), jwt("""{"typ":"user","sub":"$ID"}"""),
            jwt("""{"typ":"device","sub":"other"}"""), jwt("""{"typ":"device","sub":42}"""),
            "x".repeat(17000))) {
            assertNull(identity.deviceId(token))
        }
    }
}
private fun jwt(payload: String) = "header." +
    Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".signature"
private const val ID = "019a0a45-7000-7000-8000-abcdef000001"
