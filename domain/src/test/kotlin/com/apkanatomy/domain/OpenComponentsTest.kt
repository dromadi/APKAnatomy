package com.apkanatomy.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenComponentsTest {
    private fun component(name: String, kind: ComponentKind = ComponentKind.SERVICE, exported: Boolean = true,
        enabled: Boolean = true, permission: String? = null, writePermission: String? = null) =
        AppComponent(name, kind, exported, enabled, permission, writePermission = writePermission)

    @Test
    fun `lists enabled exported components without a permission`() {
        val components = listOf(
            component("Open"),
            component("EmptyPermission", permission = ""),
            component("Guarded", permission = "com.example.PERM"),
            component("Internal", exported = false),
            component("Disabled", enabled = false),
            component("ProviderOpenWrite", ComponentKind.PROVIDER, permission = "com.example.READ"),
            component("ProviderGuarded", ComponentKind.PROVIDER, permission = "com.example.READ", writePermission = "com.example.WRITE"),
        )
        assertEquals(listOf("Open", "EmptyPermission", "ProviderOpenWrite"), components.openToAllApps().map { it.name })
    }
}
