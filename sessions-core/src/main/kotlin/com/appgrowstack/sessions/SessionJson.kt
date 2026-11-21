package com.appgrowstack.sessions

/**
 * The small JSON the package needs to write batches and keep its state, without a library.
 * Objects are `Map<String, Any?>`, arrays `List<Any?>`, numbers `Long` or `Double`.
 */
public object SessionJson {
    public fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    public fun parse(text: String): Any? = Parser(text).run { val v = value(); skip(); require(at == text.length) { "trailing text" }; v }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> number(out, value)
            is Float -> number(out, value.toDouble())
            is Map<*, *> -> {
                out.append('{')
                value.entries.sortedBy { it.key.toString() }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun number(out: StringBuilder, value: Double) {
        require(value.isFinite()) { "not a JSON number: $value" }
        if (value == Math.floor(value) && Math.abs(value) < 1e15) out.append(value.toLong()) else out.append(value)
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val text: String) {
        var at = 0

        fun skip() { while (at < text.length && text[at].isWhitespace()) at++ }

        fun value(): Any? {
            skip()
            require(at < text.length) { "unexpected end" }
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $at")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "unexpected text at $at" }
            at += word.length
            return value
        }

        fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            skip()
            if (text[at] == '}') { at++; return map }
            while (true) {
                skip()
                val key = str()
                skip()
                require(text[at] == ':') { "expected : at $at" }
                at++
                map[key] = value()
                skip()
                when (text[at++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("expected , or } at ${at - 1}")
                }
            }
        }

        fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            at++
            skip()
            if (text[at] == ']') { at++; return list }
            while (true) {
                list.add(value())
                skip()
                when (text[at++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("expected , or ] at ${at - 1}")
                }
            }
        }

        fun str(): String {
            require(text[at] == '"') { "expected a string at $at" }
            at++
            val out = StringBuilder()
            while (true) {
                val c = text[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text[at++]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        fun num(): Any {
            val start = at
            if (text[at] == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val raw = text.substring(start, at)
            return if (raw.any { it == '.' || it == 'e' || it == 'E' }) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }
    }
}

internal fun Any?.asMap(): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this as Map<String, Any?>
}

internal fun Any?.asList(): List<Any?> = this as List<Any?>
internal fun Any?.asLong(): Long = (this as Number).toLong()
internal fun Any?.asInt(): Int = (this as Number).toInt()
internal fun Any?.asDouble(): Double = (this as Number).toDouble()
