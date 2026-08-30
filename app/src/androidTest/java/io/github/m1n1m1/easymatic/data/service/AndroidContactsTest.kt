package io.github.m1n1m1.easymatic.data.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fail-closed paths [AndroidContacts] rests on.
 *
 * None of these is reachable from the JVM — the same reason `WebViewScriptEngineTest`
 * lives here — and all three are what the engine's "null means do not dial" contract
 * actually depends on. A `SecurityException` escaping instead of a null would crash
 * a macro rather than degrade it.
 *
 * Nothing here inserts a real contact: that would need `WRITE_CONTACTS`, and what is
 * worth pinning is the failure behaviour, not the platform's own query.
 */
@RunWith(AndroidJUnit4::class)
class AndroidContactsTest {

    private val contacts = AndroidContacts(ApplicationProvider.getApplicationContext())

    @Test
    fun anUnknownLookupKeyResolvesToNothing() {
        assertNull(contacts.phoneNumber("no-such-contact-0r999"))
    }

    /** Whatever the provider makes of this, it must not come back as an exception. */
    @Test
    fun aMalformedLookupKeyReturnsNullRatherThanThrowing() {
        assertNull(contacts.phoneNumber("../../../etc"))
        assertNull(contacts.phoneNumber("%%%"))
    }

    @Test
    fun aBlankLookupKeyResolvesToNothing() {
        assertNull(contacts.phoneNumber(""))
        assertNull(contacts.phoneNumber("   "))
    }
}
