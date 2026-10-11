package com.ping.app.core.protocol

/**
 * A small, strict JSON reader/writer.
 *
 * Ping talks JSON to the relay. Everything the relay returns is attacker-controlled, so parsing is
 * done by hand with hard limits (input size, nesting depth, number of elements) instead of a
 * permissive library — there is no reflection, no polymorphic deserialisation, and no way for a
 * hostile relay to make the client instantiate anything.
 */
sealed class JsonValue {

    object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Num(val value: Double) : JsonValue() {
        fun toInt(): Int = value.toInt()
        fun toLong(): Long = value.toLong()
    }

    data class Str(val value: String) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = fields[key]
    }

    fun asObject(): Obj = this as? Obj ?: throw JsonException("expected an object")
    fun asArray(): Arr = this as? Arr ?: throw JsonException("expected an array")
    fun asString(): String = (this as? Str)?.value ?: throw JsonException("expected a string")
    fun asInt(): Int = (this as? Num)?.toInt() ?: throw JsonException("expected a number")
    fun asBool(): Boolean = (this as? Bool)?.value ?: throw JsonException("expected a boolean")
    fun optString(): String? = when (this) {
        is Null -> null
        is Str -> value
        else -> throw JsonException("expected a string or null")
    }
}

class JsonException(message: String) : Exception(message)

/** Convenience wrapper with typed accessors used by the relay DTOs. */
class JsonObject(private val value: JsonValue.Obj) {

    constructor(text: String) : this(Json.parse(text).asObject())

    fun raw(): JsonValue.Obj = value

    fun str(key: String): String = optStr(key) ?: throw JsonException("missing field '$key'")

    fun optStr(key: String): String? = value[key]?.optString()

    fun int(key: String, default: Int = 0): Int = when (val v = value[key]) {
        null, is JsonValue.Null -> default
        is JsonValue.Num -> v.toInt()
        else -> throw JsonException("field '$key' is not a number")
    }

    fun long(key: String, default: Long = 0L): Long = when (val v = value[key]) {
        null, is JsonValue.Null -> default
        is JsonValue.Num -> v.toLong()
        else -> throw JsonException("field '$key' is not a number")
    }

    fun bool(key: String, default: Boolean = false): Boolean = when (val v = value[key]) {
        null, is JsonValue.Null -> default
        is JsonValue.Bool -> v.value
        else -> throw JsonException("field '$key' is not a boolean")
    }

    fun array(key: String): List<JsonValue> = when (val v = value[key]) {
        null, is JsonValue.Null -> emptyList()
        is JsonValue.Arr -> v.items
        else -> throw JsonException("field '$key' is not an array")
    }

    fun objectOrNull(key: String): JsonObject? = (value[key] as? JsonValue.Obj)?.let { JsonObject(it) }

    fun has(key: String): Boolean = value.fields.containsKey(key)
}

object Json {

    private const val MAX_INPUT = 4 * 1024 * 1024
    private const val MAX_DEPTH = 32
    private const val MAX_ELEMENTS = 100_000

    fun parse(text: String): JsonValue {
        if (text.length > MAX_INPUT) throw JsonException("document too large (${text.length} chars)")
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd) throw JsonException("trailing content at offset ${parser.position}")
        return value
    }

    fun write(value: JsonValue): String = StringBuilder(256).also { writeTo(value, it, 0) }.toString()

    /** Build an object literal from pairs; null values are omitted. */
    fun obj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>(pairs.size)
        for ((key, v) in pairs) if (v != null) map[key] = v
        return JsonValue.Obj(map)
    }

    fun str(value: String): JsonValue.Str = JsonValue.Str(value)
    fun num(value: Int): JsonValue.Num = JsonValue.Num(value.toDouble())
    fun num(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble())
    fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)
    fun arr(values: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(values)

    private fun writeTo(value: JsonValue, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonException("nesting too deep when writing")
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(formatNumber(value.value))
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeTo(item, out, depth + 1)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    writeTo(item, out, depth + 1)
                }
                out.append('}')
            }
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) throw JsonException("cannot encode $value")
        return if (value == Math.floor(value) && Math.abs(value) < 9.2e18) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var position = 0
            private set
        private var elements = 0

        val atEnd: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (position < text.length) {
                val c = text[position]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') position++ else break
            }
        }

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonException("nesting too deep")
            if (++elements > MAX_ELEMENTS) throw JsonException("too many elements")
            skipWhitespace()
            if (atEnd) throw JsonException("unexpected end of input")
            return when (val c = text[position]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else throw JsonException("unexpected '$c' at $position")
            }
        }

        private fun literal(expected: String, value: JsonValue): JsonValue {
            if (!text.startsWith(expected, position)) throw JsonException("invalid literal at $position")
            position += expected.length
            return value
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue(depth + 1)
                fields[key] = value
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw JsonException("expected ',' or '}' but found '$c' at $position")
                }
            }
            return JsonValue.Obj(fields)
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw JsonException("expected ',' or ']' but found '$c' at $position")
                }
            }
            return JsonValue.Arr(items)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw JsonException("unterminated string")
                when (val c = text[position++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd) throw JsonException("unterminated escape")
                        when (val esc = text[position++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("truncated \\u escape")
                                val code = text.substring(position, position + 4).toIntOrNull(16)
                                    ?: throw JsonException("invalid \\u escape")
                                position += 4
                                sb.append(code.toChar())
                            }
                            else -> throw JsonException("invalid escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (c < ' ') throw JsonException("raw control character in string")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = position
            if (peek() == '-') position++
            while (!atEnd && text[position] in '0'..'9') position++
            if (!atEnd && text[position] == '.') {
                position++
                while (!atEnd && text[position] in '0'..'9') position++
            }
            if (!atEnd && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!atEnd && (text[position] == '+' || text[position] == '-')) position++
                while (!atEnd && text[position] in '0'..'9') position++
            }
            val slice = text.substring(start, position)
            val value = slice.toDoubleOrNull() ?: throw JsonException("invalid number '$slice'")
            return JsonValue.Num(value)
        }

        private fun peek(): Char = if (atEnd) '\u0000' else text[position]

        private fun next(): Char = if (atEnd) '\u0000' else text[position++]

        private fun expect(c: Char) {
            if (atEnd || text[position] != c) throw JsonException("expected '$c' at $position")
            position++
        }
    }
}
