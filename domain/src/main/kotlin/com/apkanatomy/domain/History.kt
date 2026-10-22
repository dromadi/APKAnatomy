package com.apkanatomy.domain

/**
 * A report as kept in the analysis history: the code listing (classes, methods, calls and string
 * literals, which may hold unmasked secrets) is dropped; everything else, including the manifest, stays.
 */
fun AnatomyReport.forHistory(): AnatomyReport = copy(
    code = CodeIndex(packages = code.packages, definedClasses = code.definedClasses, shortNamedClasses = code.shortNamedClasses,
        warnings = listOf(HISTORY_CODE_NOTE), packageClasses = code.packageClasses),
)

const val HISTORY_CODE_NOTE = "Laporan dibuka dari riwayat: daftar class, method, dan string tidak disimpan. Analisis ulang aplikasi atau APK-nya untuk menjelajah kode."
