package io.github.m1n1m1.easymatic.feature.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat

/**
 * This context in the App language.
 *
 * On API 33 and later the system applies the choice to the whole process, so every
 * context already is and this returns the receiver. Below that only an
 * `AppCompatActivity`'s context is localised, so an *application* context — the one
 * `GraphEditorViewModel` names nodes with and the widgets build labels with — has to
 * be re-configured by hand. Glance widgets themselves are left alone on purpose: they
 * render through the launcher, and stay in the phone's language there.
 */
internal fun Context.inAppLanguage(): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    val alreadyApplied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || locales.isEmpty
    return if (alreadyApplied) {
        this
    } else {
        val config = Configuration(resources.configuration)
        ConfigurationCompat.setLocales(config, locales)
        createConfigurationContext(config)
    }
}
