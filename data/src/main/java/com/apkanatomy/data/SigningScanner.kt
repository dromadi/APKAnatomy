package com.apkanatomy.data

import com.apkanatomy.domain.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.zip.ZipFile

/**
 * Reads which signature schemes an APK carries and who signed it. This inspects the
 * signatures; it does not verify digests (Android does that at install time).
 *
 * Format references: https://source.android.com/docs/security/features/apksigning/v2 (APK Signing Block),
 * .../v3 (v3/v3.1, proof-of-rotation) and .../v4 (.idsig next to the APK).
 */
internal class SigningScanner(private val maxBlockBytes: Long = 16L * 1024 * 1024) {
    private class FormatException(message: String) : Exception(message)
    private class Found(val cert: X509Certificate, val sha256: String, val foundIn: MutableList<String>)

    /** @param v4Signature the `.idsig` file to look for, or null when it cannot exist (single imported APK). */
    fun scan(apk: File, v4Signature: File?): SigningReport {
        val schemes = sortedSetOf<SignatureScheme>()
        val found = linkedMapOf<String, Found>()
        val other = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var rotation = false
        fun record(certBytes: ByteArray, where: String) {
            val cert = try { x509(certBytes) } catch (_: CertificateException) { warnings += "Sertifikat $where tidak dapat dibaca."; return }
            val sha256 = hex(cert.encoded, "SHA-256")
            found.getOrPut(sha256) { Found(cert, sha256, mutableListOf()) }.foundIn.let { if (where !in it) it += where }
        }

        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().toList().filter { it.name.startsWith("META-INF/") && it.name.count { c -> c == '/' } == 1 }
                if (entries.any { it.name.endsWith(".SF", ignoreCase = true) }) schemes += SignatureScheme.V1
                entries.filter { it.name.substringAfterLast('.').uppercase() in setOf("RSA", "DSA", "EC") && it.size in 1..MAX_V1_BLOCK }.forEach { entry ->
                    try {
                        zip.getInputStream(entry).use { input -> CertificateFactory.getInstance("X.509").generateCertificates(input) }
                            .filterIsInstance<X509Certificate>().forEach { record(it.encoded, "v1") }
                    } catch (_: CertificateException) { warnings += "Blok tanda tangan v1 ${entry.name} tidak dapat dibaca." }
                }
            }
        } catch (_: IOException) { warnings += "Arsip tidak dapat dibuka untuk membaca tanda tangan v1." }

        try {
            for ((id, value) in signingBlock(apk)) when (id) {
                V2_ID -> { schemes += SignatureScheme.V2; signers(value, v3 = false).forEach { record(it.cert, "v2") } }
                V3_ID, V31_ID -> {
                    val label = if (id == V3_ID) "v3" else "v3.1"
                    schemes += if (id == V3_ID) SignatureScheme.V3 else SignatureScheme.V31
                    signers(value, v3 = true).forEach { signer ->
                        rotation = rotation || signer.rotation
                        record(signer.cert, "$label (${sdkRange(signer.minSdk, signer.maxSdk)})")
                    }
                }
                SOURCE_STAMP_V1_ID, SOURCE_STAMP_V2_ID -> schemes += SignatureScheme.SOURCE_STAMP
                VERITY_PADDING_ID -> Unit
                DEPENDENCY_INFO_ID -> other += "Metadata dependensi dari Android Gradle Plugin (terenkripsi, untuk Google Play)."
                PLAY_FROSTING_ID -> other += "Metadata Google Play (frosting), ditambahkan saat distribusi lewat Play Store."
                else -> other += "Blok tidak dikenal 0x%08x (%d byte).".format(id, value.remaining())
            }
        } catch (e: FormatException) { warnings += "APK Signing Block tidak valid: ${e.message}" }
        catch (_: BufferUnderflowException) { warnings += "APK Signing Block terpotong." }
        catch (_: IOException) { warnings += "Arsip tidak dapat dibaca untuk mencari APK Signing Block." }

        if (v4Signature?.isFile == true) schemes += SignatureScheme.V4
        return SigningReport(schemes, found.values.map { it.toInfo() }, rotation, other, v4Signature != null, warnings.distinct())
    }

    /** ID-value pairs of the APK Signing Block, or empty when the APK has none (v1-only or unsigned). */
    private fun signingBlock(apk: File): List<Pair<Int, ByteBuffer>> = RandomAccessFile(apk, "r").use { raf ->
        val length = raf.length()
        if (length < EOCD_SIZE) throw FormatException("file terlalu kecil untuk ZIP.")
        val tail = ByteArray(minOf(length, EOCD_SIZE + 0xffffL).toInt()).also { raf.seek(length - it.size); raf.readFully(it) }
        val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tail.size - EOCD_SIZE downTo 0).firstOrNull { i ->
            t.getInt(i) == EOCD_MAGIC && (t.getShort(i + 20).toInt() and 0xffff) == tail.size - i - EOCD_SIZE
        } ?: throw FormatException("akhir direktori ZIP tidak ditemukan.")
        val centralDirectory = t.getInt(eocd + 16).toLong() and 0xffffffffL
        if (centralDirectory == 0xffffffffL) throw FormatException("ZIP64 belum didukung.")
        if (centralDirectory < 32 || centralDirectory > length) return emptyList()

        val footer = ByteArray(24).also { raf.seek(centralDirectory - 24); raf.readFully(it) }
        if (!footer.copyOfRange(8, 24).contentEquals(BLOCK_MAGIC)) return emptyList()
        val size = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        if (size < 24 || size > maxBlockBytes || size + 8 > centralDirectory) throw FormatException("ukuran blok $size di luar batas.")
        val block = ByteArray((size + 8).toInt()).also { raf.seek(centralDirectory - size - 8); raf.readFully(it) }
        val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getLong(0) != size) throw FormatException("dua kolom ukuran blok tidak sama.")
        val pairs = slice(b, 8, block.size - 24 - 8)
        buildList {
            while (pairs.hasRemaining()) {
                val pairLength = pairs.getLong()
                if (pairLength < 4 || pairLength > pairs.remaining()) throw FormatException("panjang entri $pairLength tidak valid.")
                val id = pairs.getInt()
                add(id to slice(pairs, pairs.position(), (pairLength - 4).toInt()))
                pairs.position(pairs.position() + (pairLength - 4).toInt())
            }
        }
    }

    private class Signer(val cert: ByteArray, val minSdk: Int?, val maxSdk: Int?, val rotation: Boolean)

    /** Parses length-prefixed v2/v3 signers; only the leaf certificate of each signer is kept. */
    private fun signers(value: ByteBuffer, v3: Boolean): List<Signer> {
        val signers = prefixed(value)
        return buildList {
            while (signers.hasRemaining()) {
                val signer = prefixed(signers)
                val signedData = prefixed(signer)
                val (minSdk, maxSdk) = if (v3) signer.getInt() to signer.getInt() else null to null
                prefixed(signedData) // digests
                val certificates = prefixed(signedData)
                val leaf = if (certificates.hasRemaining()) prefixed(certificates).let { ByteArray(it.remaining()).also(it::get) } else null
                if (v3) { signedData.getInt(); signedData.getInt() }
                var rotation = false
                val attributes = prefixed(signedData)
                while (attributes.hasRemaining()) {
                    val attribute = prefixed(attributes)
                    if (attribute.remaining() >= 4 && attribute.getInt() == PROOF_OF_ROTATION_ATTR) rotation = true
                }
                if (leaf != null) add(Signer(leaf, minSdk, maxSdk, rotation))
            }
        }
    }

    private fun prefixed(buffer: ByteBuffer): ByteBuffer {
        val length = buffer.getInt()
        if (length < 0 || length > buffer.remaining()) throw FormatException("panjang data $length melebihi sisa ${buffer.remaining()} byte.")
        return slice(buffer, buffer.position(), length).also { buffer.position(buffer.position() + length) }
    }

    private fun slice(buffer: ByteBuffer, start: Int, length: Int): ByteBuffer =
        buffer.duplicate().apply { position(start); limit(start + length) }.slice().order(ByteOrder.LITTLE_ENDIAN)

    private fun Found.toInfo(): SignerCertificate {
        val key = cert.publicKey
        val keySize = when (key) {
            is RSAPublicKey -> key.modulus.bitLength()
            is ECPublicKey -> key.params.order.bitLength()
            is DSAPublicKey -> key.params?.p?.bitLength()
            else -> null
        }
        return SignerCertificate(cert.subjectX500Principal.name, cert.issuerX500Principal.name, cert.serialNumber.toString(16),
            cert.notBefore.time, cert.notAfter.time, cert.sigAlgName, key.algorithm, keySize,
            sha256, hex(cert.encoded, "SHA-1"), hex(cert.encoded, "MD5"), foundIn)
    }

    private companion object {
        const val MAX_V1_BLOCK = 1L * 1024 * 1024
        const val EOCD_SIZE = 22
        const val EOCD_MAGIC = 0x06054b50
        val BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        const val V2_ID = 0x7109871a
        const val V3_ID = 0xf05368c0.toInt()
        const val V31_ID = 0x1b93ad61
        const val SOURCE_STAMP_V1_ID = 0x2b09189e
        const val SOURCE_STAMP_V2_ID = 0x6dff800d
        const val VERITY_PADDING_ID = 0x42726577
        const val DEPENDENCY_INFO_ID = 0x504b4453
        const val PLAY_FROSTING_ID = 0x2146444e
        const val PROOF_OF_ROTATION_ATTR = 0x3ba06f8c

        fun x509(bytes: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate

        fun hex(bytes: ByteArray, algorithm: String): String =
            MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

        fun sdkRange(min: Int?, max: Int?): String = when {
            min == null -> "semua API"
            max == null || max == Int.MAX_VALUE -> "API $min+"
            else -> "API $min–$max"
        }
    }
}

/** Scans the base and every split, and records splits whose certificates share nothing with the base's. */
internal fun scanSigning(base: File, splits: List<File>, v4Signature: File?): SigningReport {
    val report = SigningScanner().scan(base, v4Signature)
    val baseCerts = report.certificates.map { it.sha256 }.toSet()
    if (baseCerts.isEmpty()) return report
    val mismatched = splits.mapNotNull { split ->
        val certs = SigningScanner().scan(split, null).certificates.map { it.sha256 }.toSet()
        when {
            certs.isEmpty() -> "${split.name} (tidak bertanda tangan)"
            certs.none { it in baseCerts } -> split.name
            else -> null
        }
    }
    return report.copy(mismatchedSplits = mismatched)
}
