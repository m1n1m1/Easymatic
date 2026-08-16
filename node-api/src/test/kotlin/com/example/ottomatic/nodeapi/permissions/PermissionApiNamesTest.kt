package com.example.ottomatic.nodeapi.permissions

import com.example.ottomatic.core.permissions.MEDIA_PERMISSION_SPLIT_API
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.onApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Permission.onApi`, the one place the media read's rename between API levels is modelled.
 *
 * The load-bearing assertion is that **the substitution happens at exactly 33 and to
 * exactly one permission**. Getting the boundary wrong means a grant requested under one
 * name and checked under the other, which can never be satisfied — and the phone it breaks
 * on is whichever one the developer is not holding. This is a pure function precisely so
 * every boundary can be checked here rather than needing a device per API level.
 */
class PermissionApiNamesTest {

    @Test
    fun `the media read is the legacy name below the split`() {
        for (sdk in listOf(26, 28, 29, 30, 31, 32)) {
            assertEquals(
                "API $sdk should check READ_EXTERNAL_STORAGE",
                Permissions.READ_EXTERNAL_STORAGE,
                Permissions.READ_MEDIA_IMAGES.onApi(sdk),
            )
        }
    }

    @Test
    fun `the media read is the modern name at and above the split`() {
        for (sdk in listOf(MEDIA_PERMISSION_SPLIT_API, 34, 35, 36)) {
            assertEquals(
                "API $sdk should check READ_MEDIA_IMAGES",
                Permissions.READ_MEDIA_IMAGES,
                Permissions.READ_MEDIA_IMAGES.onApi(sdk),
            )
        }
    }

    @Test
    fun `the split is at exactly 33`() {
        assertEquals(
            Permissions.READ_EXTERNAL_STORAGE,
            Permissions.READ_MEDIA_IMAGES.onApi(MEDIA_PERMISSION_SPLIT_API - 1),
        )
        assertEquals(
            Permissions.READ_MEDIA_IMAGES,
            Permissions.READ_MEDIA_IMAGES.onApi(MEDIA_PERMISSION_SPLIT_API),
        )
    }

    /**
     * Nothing else is substituted, on any version.
     *
     * A rename is a *specific* claim about one permission; a resolver that quietly
     * rewrote others would be a second, invisible permission model.
     */
    @Test
    fun `every other permission is returned unchanged`() {
        val others = listOf(
            Permissions.READ_CONTACTS,
            Permissions.READ_CALENDAR,
            Permissions.ACCESS_FINE_LOCATION,
            Permissions.BLUETOOTH_CONNECT,
            Permissions.POST_NOTIFICATIONS,
            Permissions.WRITE_EXTERNAL_STORAGE,
            Permissions.ACCESS_MEDIA_LOCATION,
            Permissions.READ_EXTERNAL_STORAGE,
        )
        for (permission in others) {
            for (sdk in listOf(26, 32, 33, 36)) {
                assertEquals(
                    "${permission.simpleName} must not be rewritten on API $sdk",
                    permission,
                    permission.onApi(sdk),
                )
            }
        }
    }
}
