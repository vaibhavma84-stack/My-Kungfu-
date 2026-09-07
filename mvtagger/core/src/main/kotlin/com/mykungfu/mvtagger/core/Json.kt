package com.mykungfu.mvtagger.core

/**
 * A small JSON reader.
 *
 * `org.json` ships with Android but not with a plain JVM, and the response
 * parsing is the part most worth testing on a laptop. Rather than have the
 * tested code and the shipped code be two different parsers, parsing lives here
 * and both sides use it.
 *
 * Read-only, and deliberately lenient about what it ignores: search APIs add
 * fields all the time and an unknown field must never fail a lookup.
 */
sealed class Json {
    data object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json()
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()

    /** Field of an object, or [Null] for anything else. Never throws. */
    operator fun get(key: String): Json = (this as? Obj)?.fields?.get(key) ?: Null

    /** Element of an array, or [Null]. Never throws. */
    operator fun get(index: Int): Json = (this as? Arr)?.items?.getOrNull(index) ?: Null

    val array: List<Json> get() = (this as? Arr)?.items ?: emptyList()

    /** The value as text. Numbers stringify, so `"date": 2019` reads like `"2019"`. */
    val string: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (value == Math.floor(value) && !value.isInfinite())
                value.toLong().toString() else value.toString()
            is Bool -> value.toString()
            else -> null
        }

    val int: Int?
        get() = (this as? Num)?.value?.toInt() ?: (this as? Str)?.value?.trim()?.toIntOrNull()

    companion object {
        fun parse(text: String): Json {
            val p = Parser(text)
            val v = p.readValue()
            p.skipWhitespace()
            return v
        }

        /** Parses, or returns [Null] on malformed input instead of throwing. */
        fun parseOrNull(text: String): Json =
            try {
                parse(text)
            } catch (e: Exception) {
                Null
            }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Json {
            skipWhitespace()
            if (i >= s.length) throw IllegalArgumentException("unexpected end of JSON")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Str(readString())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> readNumber()
            }
        }

        private fun literal(word: String, value: Json): Json {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return value
        }

        private fun readObject(): Json {
            i++ // {
            val fields = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                require(i < s.length && s[i] == ':') { "expected ':' at $i" }
                i++
                fields[key] = readValue()
                skipWhitespace()
                require(i < s.length) { "unterminated object" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return Obj(fields)
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $i")
                }
            }
        }

        private fun readArray(): Json {
            i++ // [
            val items = ArrayList<Json>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                require(i < s.length) { "unterminated array" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return Arr(items)
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $i")
                }
            }
        }

        private fun readString(): String {
            require(i < s.length && s[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i]
                when (c) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        require(i < s.length) { "unterminated escape" }
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            // \uXXXX matters here: Devanagari and Tamil come back
                            // escaped from some endpoints and raw from others.
                            'u' -> {
                                require(i + 4 < s.length) { "short unicode escape" }
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape: $e")
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun readNumber(): Json {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' ||
                        s[i] == 'E' || s[i] == '-' || s[i] == '+')
            ) i++
            val text = s.substring(start, i)
            return Num(text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number: $text"))
        }
    }
}
