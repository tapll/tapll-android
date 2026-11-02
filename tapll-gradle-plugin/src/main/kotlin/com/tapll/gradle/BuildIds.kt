package com.tapll.gradle

import java.util.UUID

object BuildIds {
    const val ASSET_NAME = "tapll-build-id.txt"

    /** The contract wants `^[0-9a-f-]{7,64}$`; `UUID.toString()` is already lowercase, and is asserted here anyway. */
    fun generate(): String = UUID.randomUUID().toString().lowercase()

    private val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun isValid(id: String): Boolean = pattern.matches(id)
}
