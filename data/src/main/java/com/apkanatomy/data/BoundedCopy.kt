package com.apkanatomy.data

import java.io.InputStream
import java.io.OutputStream

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
    require(limit > 0)
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count == -1) return total
        if (count == 0) continue
        require(count.toLong() <= limit - total) { "APK melebihi batas 512 MiB." }
        output.write(buffer, 0, count)
        total += count
    }
}
