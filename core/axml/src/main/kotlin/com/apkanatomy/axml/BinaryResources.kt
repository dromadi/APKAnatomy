package com.apkanatomy.axml

import java.nio.ByteBuffer

// Shared pieces of Android's compiled resource formats (ResourceTypes.h): string pools and Res_value.

internal const val RES_STRING_POOL_TYPE = 0x0001
internal const val NO_INDEX = -1
private const val UTF8_FLAG = 1 shl 8

internal fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xffff

/** Reads the ResStringPool chunk at [start]. */
internal fun readStringPool(buf: ByteBuffer, bytes: ByteArray, start: Int): List<String> {
    val headerSize = buf.u16(start + 2)
    val chunkSize = buf.getInt(start + 4)
    val count = buf.getInt(start + 8)
    val flags = buf.getInt(start + 16)
    val stringsStart = buf.getInt(start + 20)
    if (count < 0 || headerSize + count.toLong() * 4 > chunkSize) {
        throw AxmlException("Invalid string pool ($count strings)")
    }
    val utf8 = flags and UTF8_FLAG != 0
    return List(count) { i ->
        val offset = start + stringsStart + buf.getInt(start + headerSize + i * 4)
        if (utf8) readUtf8(bytes, offset) else readUtf16(buf, bytes, offset)
    }
}

private fun readUtf8(bytes: ByteArray, offset: Int): String {
    var p = offset
    // UTF-16 length first (unused), then the UTF-8 byte length; each takes 1 or 2 bytes.
    p += if (bytes[p].toInt() and 0x80 != 0) 2 else 1
    var length = bytes[p++].toInt() and 0xff
    if (length and 0x80 != 0) length = ((length and 0x7f) shl 8) or (bytes[p++].toInt() and 0xff)
    return String(bytes, p, length, Charsets.UTF_8)
}

private fun readUtf16(buf: ByteBuffer, bytes: ByteArray, offset: Int): String {
    var p = offset
    var length = buf.u16(p)
    p += 2
    if (length and 0x8000 != 0) {
        length = ((length and 0x7fff) shl 16) or buf.u16(p)
        p += 2
    }
    return String(bytes, p, length * 2, Charsets.UTF_16LE)
}

// Res_value data types.
internal const val TYPE_NULL = 0x00
internal const val TYPE_REFERENCE = 0x01
internal const val TYPE_ATTRIBUTE = 0x02
internal const val TYPE_STRING = 0x03
private const val TYPE_FLOAT = 0x04
private const val TYPE_DIMENSION = 0x05
private const val TYPE_FRACTION = 0x06
internal const val TYPE_DYNAMIC_REFERENCE = 0x07
private const val TYPE_DYNAMIC_ATTRIBUTE = 0x08
private const val TYPE_INT_DEC = 0x10
private const val TYPE_INT_HEX = 0x11
private const val TYPE_INT_BOOLEAN = 0x12
private const val TYPE_FIRST_COLOR = 0x1c
private const val TYPE_LAST_COLOR = 0x1f

private val DIMENSION_UNITS = listOf("px", "dip", "sp", "pt", "in", "mm")
private val RADIX_MULTIPLIERS = floatArrayOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / (1L shl 31))

/**
 * Renders a Res_value the way it would appear in source XML.
 * [name] turns a resource ID into "type/name" when a resource table is available.
 */
internal fun formatTypedValue(dataType: Int, data: Int, string: (Int) -> String?, name: (Int) -> String?): String = when (dataType) {
    TYPE_NULL -> ""
    TYPE_REFERENCE, TYPE_DYNAMIC_REFERENCE -> if (data == 0) "@null" else name(data)?.let { "@$it" } ?: "@0x%08x".format(data)
    TYPE_ATTRIBUTE, TYPE_DYNAMIC_ATTRIBUTE -> name(data)?.let { "?$it" } ?: "?0x%08x".format(data)
    TYPE_STRING -> string(data).orEmpty()
    TYPE_FLOAT -> formatNumber(Float.fromBits(data))
    TYPE_DIMENSION -> formatNumber(complexToFloat(data)) + DIMENSION_UNITS.getOrElse(data and 0xf) { "" }
    TYPE_FRACTION -> formatNumber(complexToFloat(data) * 100) + if (data and 0xf == 0) "%" else "%p"
    TYPE_INT_DEC -> data.toString()
    TYPE_INT_HEX -> "0x%x".format(data)
    TYPE_INT_BOOLEAN -> (data != 0).toString()
    in TYPE_FIRST_COLOR..TYPE_LAST_COLOR -> "#%08x".format(data)
    else -> "0x%08x".format(data)
}

private fun complexToFloat(complex: Int): Float =
    (complex and 0xffffff00.toInt()) * RADIX_MULTIPLIERS[(complex shr 4) and 3]

private fun formatNumber(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
