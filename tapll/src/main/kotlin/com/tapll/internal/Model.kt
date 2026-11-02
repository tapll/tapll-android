package com.tapll.internal

import com.tapll.Environment
import com.tapll.FeatureRole
import com.tapll.PrivacyMode

/** Reserved `$`-prefixed names emitted by the SDK itself; customers cannot track these directly. */
internal enum class AutoEvent(val wire: String) {
    FirstOpen("\$first_open"),
    SessionStart("\$session_start"),
    SessionEnd("\$session_end"),
    Foreground("\$foreground"),
    Background("\$background"),
    ScreenView("\$screen_view"),
    AppInstall("\$app_install"),
    AppUpdate("\$app_update"),
    DeepLink("\$deep_link"),
}

/** One event on the wire (`events[]`). Keys are snake_case per contract; no extra fields allowed. */
internal data class Event(
    val eventId: String,
    val sessionId: String,
    val name: String,
    /** Client wall-clock time in ms since epoch. */
    val timestamp: Long,
    val screen: String? = null,
    val properties: Map<String, Any>? = null,
    val feature: String? = null,
    val role: FeatureRole? = null,
) {
    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["event_id"] = eventId
        m["session_id"] = sessionId
        m["name"] = name
        m["timestamp"] = timestamp
        screen?.let { m["screen"] = it }
        properties?.let { m["properties"] = it }
        feature?.let { m["feature"] = it }
        role?.let { m["role"] = it.wire }
        return m
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(m: Map<String, Any?>): Event? {
            val role = (m["role"] as? String)?.let { w -> FeatureRole.entries.firstOrNull { it.wire == w } }
            return Event(
                eventId = m["event_id"] as? String ?: return null,
                sessionId = m["session_id"] as? String ?: return null,
                name = m["name"] as? String ?: return null,
                timestamp = (m["timestamp"] as? Number)?.toLong() ?: return null,
                screen = m["screen"] as? String,
                properties = (m["properties"] as? Map<String, Any?>)?.filterValues { it != null }?.mapValues { it.value!! },
                feature = m["feature"] as? String,
                role = role,
            )
        }
    }
}

internal data class DeviceContext(
    val appVersion: String,
    val appBuild: String,
    val sdkVersion: String,
    /** `<name>/<version>` of a Flutter or React Native wrapper; absent (never null) for a native app. */
    val sdkWrapper: String? = null,
    val osVersion: String,
    val deviceModel: String,
    val locale: String,
    val timezone: String,
) {
    /** The contract's v1 wire format knows two values; this SDK is only ever "Android". */
    val osName: String get() = OS_NAME

    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["app_version"] = appVersion
        m["app_build"] = appBuild
        m["sdk_version"] = sdkVersion
        sdkWrapper?.let { m["sdk_wrapper"] = it }
        m["os_name"] = osName
        m["os_version"] = osVersion
        m["device_model"] = deviceModel
        m["locale"] = locale
        m["timezone"] = timezone
        return m
    }

    companion object {
        const val OS_NAME = "Android"

        fun fromJson(m: Map<String, Any?>): DeviceContext? {
            return DeviceContext(
                appVersion = m["app_version"] as? String ?: return null,
                appBuild = m["app_build"] as? String ?: return null,
                sdkVersion = m["sdk_version"] as? String ?: return null,
                sdkWrapper = m["sdk_wrapper"] as? String,
                osVersion = m["os_version"] as? String ?: return null,
                deviceModel = m["device_model"] as? String ?: return null,
                locale = m["locale"] as? String ?: return null,
                timezone = m["timezone"] as? String ?: return null,
            )
        }
    }
}

/** `identity` is always present; keys are omitted (never null) when absent. Empty in strict_anonymous. */
internal data class Identity(
    val installId: String? = null,
    val userId: String? = null,
    /** What the app says about the person (`name`, `email`, `plan`...). Omitted when empty. */
    val traits: Map<String, String>? = null,
) {
    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        installId?.let { m["install_id"] = it }
        userId?.let { m["user_id"] = it }
        traits?.takeIf { it.isNotEmpty() }?.let { m["traits"] = it }
        return m
    }

    companion object {
        val ANONYMOUS = Identity()
    }
}

internal data class EventBatch(
    val batchId: String,
    val environment: Environment,
    val privacyMode: PrivacyMode,
    val sentAt: Long,
    val device: DeviceContext,
    val identity: Identity,
    val events: List<Event>,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "contract_version" to SdkInfo.CONTRACT_VERSION,
        "batch_id" to batchId,
        "environment" to environment.wire,
        "privacy_mode" to privacyMode.wire,
        "sent_at" to sentAt,
        "device" to device.toJson(),
        "identity" to identity.toJson(),
        "events" to events.map { it.toJson() },
    )

    fun encode(): ByteArray = Json.writeBytes(toJson())
}
