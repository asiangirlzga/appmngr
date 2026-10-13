package com.tvapps.manager

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

enum class Level { USER, SYSTEM_CAUTION, PROTECTED }

data class Verdict(val level: Level, val reason: String)

/**
 * Decides whether a package may be touched. Anything the TV needs to boot, show a
 * home screen, take input, switch HDMI sources or render web content is PROTECTED
 * and the UI never offers a remove action for it.
 *
 * Layers (first match wins):
 *  1. Live checks on THIS device: launcher, settings, keyboard, TV inputs, WebView,
 *     accessibility services, package installer.
 *  2. Known core package names / prefixes (framework, Google services, vendor SoC stacks).
 *  3. Structural checks: shared system UID, persistent flag, priv-app / apex location,
 *     platform signing key.
 *  Everything else that is a system app is SYSTEM_CAUTION (disable / ADB only);
 *  everything else is a normal USER app.
 */
class SafetyGuard(private val ctx: Context) {

    private val pm: PackageManager = ctx.packageManager
    private val dynamic = HashMap<String, String>()

    init {
        collectDynamic()
    }

    // ---------------------------------------------------------------- live checks

    private fun protect(pkg: String?, reason: String) {
        if (pkg != null && !dynamic.containsKey(pkg)) dynamic[pkg] = reason
    }

    private fun query(intent: Intent): List<ResolveInfo> = try {
        pm.queryIntentActivities(
            intent,
            if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        )
    } catch (e: Exception) {
        emptyList()
    }

    private fun isSystemPkg(pkg: String): Boolean = try {
        val f = pm.getApplicationInfo(pkg, 0).flags
        (f and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (e: Exception) {
        false
    }

    private fun collectDynamic() {
        protect(ctx.packageName, "This app")

        // Home / launcher
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val homes = query(home).mapNotNull { it.activityInfo?.packageName }.distinct()
            val def = try {
                pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            }
            for (h in homes) {
                if (homes.size == 1 || h == def || isSystemPkg(h)) protect(h, "Home screen / launcher")
            }
            if (def != null) protect(def, "Default launcher")
        } catch (ignored: Exception) {
        }

        // Settings screens
        val settingsActions = listOf(
            Settings.ACTION_SETTINGS, Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_DATE_SETTINGS,
            Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_APPLICATION_SETTINGS
        )
        for (a in settingsActions) {
            query(Intent(a)).forEach { protect(it.activityInfo?.packageName, "System settings") }
        }

        // Package installer (we depend on it for uninstalling)
        query(Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://dummy/a.apk"), "application/vnd.android.package-archive"
        )).forEach { protect(it.activityInfo?.packageName, "Package installer") }
        query(Intent(Intent.ACTION_DELETE, Uri.parse("package:dummy.pkg")))
            .forEach { protect(it.activityInfo?.packageName, "Package installer") }

        // Keyboards
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList.map { it.packageName }.distinct()
            val def = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            for (p in enabled) {
                if (enabled.size == 1 || p == def || isSystemPkg(p)) protect(p, "Keyboard / input method")
            }
            protect(def, "Active keyboard")
        } catch (ignored: Exception) {
        }

        // HDMI / tuner / live TV inputs provided by system apps
        try {
            val tv = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            tv?.tvInputList?.forEach {
                val p = it.serviceInfo.packageName
                if (isSystemPkg(p)) protect(p, "TV input (HDMI / tuner)")
            }
        } catch (ignored: Exception) {
        }

        // WebView provider
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                protect(WebView.getCurrentWebViewPackage()?.packageName, "System WebView")
            }
        } catch (ignored: Exception) {
        }

        // Active accessibility services (voice / screen reader)
        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .forEach { protect(it.resolveInfo?.serviceInfo?.packageName, "Active accessibility service") }
        } catch (ignored: Exception) {
        }
    }

    // ---------------------------------------------------------------- name rules

    private val exactCore = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.android.shell", "com.android.keychain", "com.android.certinstaller",
        "com.android.bluetooth", "com.android.nfc", "com.android.inputdevices",
        "com.android.location.fused", "com.android.server.telecom", "com.android.phone",
        "com.android.externalstorage", "com.android.documentsui", "com.android.mtp",
        "com.android.managedprovisioning", "com.android.proxyhandler",
        "com.android.captiveportallogin", "com.android.networkstack", "com.android.vpndialogs",
        "com.android.companiondevicemanager", "com.android.packageinstaller",
        "com.android.permissioncontroller", "com.android.webview", "com.android.vending",
        "com.android.tv", "com.android.statementservice", "com.android.se",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.webview",
        "com.google.android.trichromelibrary", "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.google.android.documentsui",
        "com.google.android.networkstack", "com.google.android.ext.services",
        "com.google.android.ext.shared", "com.google.android.setupwizard",
        "com.google.android.tungsten.setupwraith", "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher", "com.google.android.tvrecommendations",
        "com.google.android.apps.tv.launcherx", "com.google.android.tv.remote.service",
        "com.google.android.backdrop", "com.google.android.configupdater",
        "com.google.android.onetimeinitializer", "com.google.android.partnersetup"
    )

    private val corePrefixes = listOf(
        "com.android.providers." to "Core system data provider",
        "com.google.android.providers." to "Core system data provider",
        "com.android.inputmethod." to "System keyboard",
        "com.android.internal." to "Framework component",
        "com.android.server." to "Framework component",
        "com.android.modules." to "Mainline system module",
        "com.android.cellbroadcast" to "Core system service",
        "com.android.wifi" to "Wi-Fi stack",
        "com.android.tv." to "Android TV system component",
        "com.google.android.permission" to "Permission system",
        "com.google.android.modulemetadata" to "Mainline system module",
        "com.mediatek." to "Chipset / vendor core (MediaTek)",
        "com.mstar." to "Chipset / vendor core (MStar)",
        "com.amlogic." to "Chipset / vendor core (Amlogic)",
        "com.droidlogic." to "Chipset / vendor core (Amlogic)",
        "com.realtek." to "Chipset / vendor core (Realtek)",
        "com.hisilicon." to "Chipset / vendor core (HiSilicon)",
        "com.broadcom." to "Chipset / vendor core (Broadcom)",
        "com.qualcomm." to "Chipset / vendor core (Qualcomm)",
        "com.nvidia." to "Chipset / vendor core (NVIDIA)",
        "vendor." to "Vendor core"
    )

    private fun nameRule(pkg: String): String? {
        if (pkg in exactCore) return "Core Android TV component"
        for ((prefix, why) in corePrefixes) if (pkg.startsWith(prefix)) return why
        return null
    }

    // ---------------------------------------------------------------- verdict

    fun verdict(pkg: String): Verdict {
        val info = try {
            pm.getPackageInfo(pkg, 0)
        } catch (e: Exception) {
            return Verdict(Level.PROTECTED, "Package not readable")
        }
        val ai = info.applicationInfo ?: return Verdict(Level.PROTECTED, "Package not readable")

        dynamic[pkg]?.let { return Verdict(Level.PROTECTED, it) }
        nameRule(pkg)?.let { return Verdict(Level.PROTECTED, it) }

        val isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        if (!isSystem) return Verdict(Level.USER, "Installed by you or from an app store")

        if (info.sharedUserId?.startsWith("android.uid.") == true)
            return Verdict(Level.PROTECTED, "Runs inside a core system process")
        if ((ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0)
            return Verdict(Level.PROTECTED, "Persistent system component")
        val dir = ai.sourceDir ?: ""
        if (dir.contains("/priv-app/") || dir.startsWith("/apex/"))
            return Verdict(Level.PROTECTED, "Privileged system component")
        try {
            if (pm.checkSignatures(pkg, "android") == PackageManager.SIGNATURE_MATCH)
                return Verdict(Level.PROTECTED, "Signed with the device platform key")
        } catch (ignored: Exception) {
            return Verdict(Level.PROTECTED, "Could not verify signature")
        }
        return Verdict(Level.SYSTEM_CAUTION, "Pre-installed app. Likely safe to remove, but check first")
    }

    companion object {
        fun adbRemoveCommand(pkg: String) = "adb shell pm uninstall -k --user 0 $pkg"
        fun adbRestoreCommand(pkg: String) = "adb shell cmd package install-existing $pkg"
    }
}
