package com.moblized.jellydash

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Swaps the launcher icon between the light and dark versions to match the app's theme. */
object IconSwitcher {
    private const val LIGHT = "com.moblized.jellydash.LauncherLight"
    private const val DARK = "com.moblized.jellydash.LauncherDark"

    fun apply(context: Context, dark: Boolean) {
        runCatching {
            val pm = context.packageManager
            val light = ComponentName(context.packageName, LIGHT)
            val darkAlias = ComponentName(context.packageName, DARK)
            val enable = if (dark) darkAlias else light
            val disable = if (dark) light else darkAlias
            // Enable the new entry first so the app is never left without a launcher icon.
            if (!isEnabled(pm, enable, defaultEnabled = enable == light)) {
                pm.setComponentEnabledSetting(enable, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            if (isEnabled(pm, disable, defaultEnabled = disable == light)) {
                pm.setComponentEnabledSetting(disable, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun isEnabled(pm: PackageManager, c: ComponentName, defaultEnabled: Boolean): Boolean =
        when (pm.getComponentEnabledSetting(c)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
            else -> false
        }
}
