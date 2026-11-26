package com.example.accessibility_service.notification

import android.util.Base64
import com.example.accessibility_service.networking.isCanonicalNotificationId
import java.util.Locale
import org.json.JSONObject

/**
 * Reads LOCAL storage identity from the enrolled JWT; does not authenticate or verify a signature.
 * Every HTTP route still authenticates the entire bearer token on the server.
 */
internal class DeviceTokenIdentity(
    private val decodePayload: (String) -> String = {
        String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    },
) {
    fun deviceId(token: String): String? = try {
        require(token.length <= 16 * 1024)
        val parts = token.split('.')
        require(parts.size == 3 && parts.all { it.isNotBlank() })
        val claims = JSONObject(decodePayload(parts[1]))
        require(claims.get("typ") == "device")
        val subject = claims.get("sub")
        require(subject is String && isCanonicalNotificationId(subject))
        subject.lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }
}
