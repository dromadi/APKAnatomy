package com.apkanatomy.domain

/**
 * Whether code for a requested permission was seen. [behaviorId] is the Perilaku finding that needs it;
 * [usedBy] lists where, empty when no such call was found in the code read.
 */
data class PermissionUse(val permission: String, val behaviorTitle: String, val usedBy: List<String>, val inAppPackage: Int) {
    val seen: Boolean get() = usedBy.isNotEmpty()
}

/** An API group called in code whose permission the manifest does not request. */
data class UndeclaredUse(val behaviorTitle: String, val permissions: List<String>, val usedBy: List<String>)

private const val P = "android.permission."

/** Permissions and the behaviour (Perilaku rule id) whose API they guard. Only pairs the scanner can see are listed. */
private val GUARDED: List<Pair<List<String>, String>> = listOf(
    listOf("${P}ACCESS_FINE_LOCATION", "${P}ACCESS_COARSE_LOCATION") to "location",
    listOf("${P}CAMERA") to "camera",
    listOf("${P}RECORD_AUDIO") to "microphone",
    listOf("${P}READ_PHONE_STATE", "${P}READ_PHONE_NUMBERS", "${P}READ_PRIVILEGED_PHONE_STATE") to "device-id",
    listOf("${P}SEND_SMS") to "sms-send",
    listOf("${P}QUERY_ALL_PACKAGES") to "installed-apps",
    listOf("com.google.android.gms.permission.AD_ID") to "ad-id",
)

/** For each requested permission the scanner can match, whether the code calls an API that needs it. */
fun AnatomyReport.permissionUse(): List<PermissionUse> {
    val requested = permissions.map { it.name }.toSet()
    val byId = behaviors.findings.associateBy { it.id }
    return GUARDED.flatMap { (perms, id) ->
        perms.filter { it in requested }.map { perm ->
            val finding = byId[id]
            PermissionUse(perm, finding?.title ?: TITLES.getValue(id), finding?.usedBy.orEmpty(), finding?.inAppPackage ?: 0)
        }
    }
}

/** API groups seen in code without any of their permissions requested (the call would fail, or is guarded by a check). */
fun AnatomyReport.undeclaredUse(): List<UndeclaredUse> {
    val requested = permissions.map { it.name }.toSet()
    val byId = behaviors.findings.associateBy { it.id }
    return GUARDED.mapNotNull { (perms, id) ->
        val finding = byId[id] ?: return@mapNotNull null
        // AD_ID is merged in by Play services; installed-apps also works with <queries>, so neither proves a missing permission.
        if (id == "ad-id" || id == "installed-apps" || perms.any { it in requested }) null else UndeclaredUse(finding.title, perms, finding.usedBy)
    }
}

private val TITLES = mapOf("location" to "Membaca lokasi", "camera" to "Membuka kamera", "microphone" to "Merekam audio", "device-id" to "Membaca ID telepon/SIM",
    "sms-send" to "Mengirim SMS", "installed-apps" to "Mendaftar aplikasi terinstal", "ad-id" to "Membaca Advertising ID")
