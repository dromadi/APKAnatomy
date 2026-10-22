package com.apkanatomy.data

import java.io.InputStream
import java.io.OutputStream

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long, checkCancelled: () -> Unit = {}): Long {
    require(limit > 0)
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count == -1) return total
        if (count == 0) continue
        if ((total and 0xFFFFFL) < count) checkCancelled() // about once per MiB
        require(count.toLong() <= limit - total) { "APK melebihi batas 512 MiB." }
        output.write(buffer, 0, count)
        total += count
    }
}
