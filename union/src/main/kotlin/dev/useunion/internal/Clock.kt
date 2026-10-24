package dev.useunion.internal

internal interface Clock {
    fun nowMs(): Long
}

internal object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
