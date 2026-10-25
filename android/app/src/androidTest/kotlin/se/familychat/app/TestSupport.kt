package se.familychat.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore

/** The app under test (debug or qa build): the process the tests run in. */
val appContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Removes a Keystore entry a test created; absent entries are fine. */
fun deleteKeystoreAlias(alias: String) {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (ks.containsAlias(alias)) ks.deleteEntry(alias)
}

fun keystoreHasAlias(alias: String): Boolean =
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)
