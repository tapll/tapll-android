package dev.useunion.internal

/** Mirror of `packages/contract/src/limits.ts`. A unit test cross-checks these against the JSON Schema copies. */
internal object Limits {
    const val MAX_TRAITS = 8
    const val TRAIT_KEY_MAX = 40
    const val TRAIT_VALUE_MAX = 256
    const val BATCH_MAX_EVENTS = 100
    const val BATCH_MAX_BYTES = 256 * 1024
    const val EVENT_NAME_MAX = 64
    const val MAX_PROPERTIES = 32
    const val PROPERTY_KEY_MAX = 64
    const val PROPERTY_STRING_MAX = 256
    const val SCREEN_NAME_MAX = 128
    const val USER_ID_MAX = 128
    const val FEATURE_KEY_MAX = 64
    const val WRAPPER_MAX = 32
    val NAME_PATTERN = Regex("^[a-z][a-z0-9_]*$")
}

internal class ValidationException(message: String) : Exception(message)

/**
 * Every check throws [ValidationException]; the pipeline logs it and drops the event. Strings are
 * measured in UTF-16 units — what zod's `.max()` counts — so anything that passes here passes there.
 */
internal object Validation {
    fun customName(name: String) {
        if (name.startsWith("$")) throw ValidationException("\"$name\": names starting with $ are reserved for automatic events")
        if (name.length > Limits.EVENT_NAME_MAX || !Limits.NAME_PATTERN.matches(name)) {
            throw ValidationException("\"$name\": name must be lowercase snake_case starting with a letter, max ${Limits.EVENT_NAME_MAX} chars")
        }
    }

    /** `null` is fine: most events belong to no feature. */
    fun featureKey(key: String?) {
        if (key == null) return
        if (key.length > Limits.FEATURE_KEY_MAX || !Limits.NAME_PATTERN.matches(key)) {
            throw ValidationException("feature key \"$key\" must be lowercase snake_case starting with a letter, max ${Limits.FEATURE_KEY_MAX} chars")
        }
    }

    /** Property values are string (<= 256), finite number or boolean. Anything else drops the event. */
    fun properties(properties: Map<String, Any>) {
        if (properties.size > Limits.MAX_PROPERTIES) throw ValidationException("${properties.size} properties, max ${Limits.MAX_PROPERTIES}")
        for ((key, value) in properties) {
            if (key.isEmpty() || key.length > Limits.PROPERTY_KEY_MAX) throw ValidationException("property key \"$key\" must be 1-${Limits.PROPERTY_KEY_MAX} chars")
            when (value) {
                is String -> if (value.length > Limits.PROPERTY_STRING_MAX) throw ValidationException("property \"$key\" exceeds ${Limits.PROPERTY_STRING_MAX} chars")
                is Boolean -> Unit
                is Int, is Long, is Short, is Byte -> Unit
                is Double -> if (!value.isFinite()) throw ValidationException("property \"$key\" is not a finite number")
                is Float -> if (!value.isFinite()) throw ValidationException("property \"$key\" is not a finite number")
                else -> throw ValidationException("property \"$key\" must be a String, Number or Boolean")
            }
        }
    }

    fun screen(screen: String?) {
        if (screen != null && screen.length > Limits.SCREEN_NAME_MAX) throw ValidationException("screen name exceeds ${Limits.SCREEN_NAME_MAX} chars")
    }

    /** Validated as a whole: one bad key rejects the call rather than sending a half-set. */
    fun traits(traits: Map<String, String>) {
        if (traits.size > Limits.MAX_TRAITS) throw ValidationException("${traits.size} traits, max ${Limits.MAX_TRAITS}")
        for ((key, value) in traits) {
            if (key.isEmpty() || key.length > Limits.TRAIT_KEY_MAX) throw ValidationException("trait key \"$key\" must be 1-${Limits.TRAIT_KEY_MAX} chars")
            if (value.length > Limits.TRAIT_VALUE_MAX) throw ValidationException("trait \"$key\" exceeds ${Limits.TRAIT_VALUE_MAX} chars")
        }
    }

    fun userId(userId: String) {
        if (userId.isEmpty() || userId.length > Limits.USER_ID_MAX) throw ValidationException("user id must be 1-${Limits.USER_ID_MAX} chars")
    }
}
