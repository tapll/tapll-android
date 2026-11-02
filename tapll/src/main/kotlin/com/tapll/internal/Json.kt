package com.tapll.internal

/**
 * A small JSON reader/writer, because the two obvious choices are both wrong here: `org.json` is a
 * stub in plain JVM unit tests (so every pipeline test would need Robolectric), and a serialization
 * library is a dependency the SDK would impose on every app for four dozen lines of work.
 *
 * Values are `Map<String, Any?>` (insertion ordered), `List<Any?>`, `String`, `Boolean`, `Long`,
 * `Double` and `null`. Integral doubles below 1e15 are written without a fraction, so `minutes: 30`
 * stays `30` on the wire — the same rule as the iOS `PropertyValue` encoder.
 */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    fun writeBytes(value: Any?): ByteArray = write(value).toByteArray(Charsets.UTF_8)

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Int -> out.append(value)
            is Long -> out.append(value)
            is Short, is Byte -> out.append(value.toString())
            is Float -> appendDouble(out, value.toDouble())
            is Double -> appendDouble(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k as String)
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun appendDouble(out: StringBuilder, d: Double) {
        require(d.isFinite()) { "non-finite number" }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append(d.toLong()) else out.append(d.toString())
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** Throws [IllegalArgumentException] on malformed input; callers that read untrusted bytes use [parseOrNull]. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        require(reader.atEnd()) { "trailing characters at ${reader.pos}" }
        return value
    }

    fun parseOrNull(text: String): Any? = try { parse(text) } catch (_: IllegalArgumentException) { null }

    @Suppress("UNCHECKED_CAST")
    fun parseObjectOrNull(text: String): Map<String, Any?>? = parseOrNull(text) as? Map<String, Any?>

    private class Reader(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                require(pos < s.length && s[pos] == '"') { "expected key at $pos" }
                val key = readString()
                skipWhitespace()
                require(pos < s.length && s[pos] == ':') { "expected ':' at $pos" }
                pos++
                map[key] = readValue()
                skipWhitespace()
                require(pos < s.length) { "unterminated object" }
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWhitespace()
                require(pos < s.length) { "unterminated array" }
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            val sb = StringBuilder()
            pos++
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "bad escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad unicode escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$text'")
        }
    }
}
