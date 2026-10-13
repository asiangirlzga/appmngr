package com.tvapps.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class AppItem(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val verdict: Verdict,
    val enabled: Boolean
)

class MainActivity : Activity() {

    private enum class Filter(val label: String) {
        USER("User apps"), SYSTEM("System (caution)"), PROTECTED("Protected"), ALL("All")
    }

    private var filter = Filter.USER
    private var all: List<AppItem> = emptyList()
    private lateinit var adapter: AppAdapter
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var buttons: Map<Filter, Button>

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        buttons = mapOf(
            Filter.USER to findViewById<Button>(R.id.btn_user),
            Filter.SYSTEM to findViewById<Button>(R.id.btn_system),
            Filter.PROTECTED to findViewById<Button>(R.id.btn_protected),
            Filter.ALL to findViewById<Button>(R.id.btn_all)
        )
        for ((f, b) in buttons) {
            b.setOnClickListener { filter = f; applyFilter() }
        }

        adapter = AppAdapter()
        list.adapter = adapter
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            showDetails(adapter.getItem(pos))
        }
    }

    override fun onResume() {
        super.onResume()
        load() // refresh after returning from an uninstall / app info screen
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ data

    private fun load() {
        status.text = "Scanning apps…"
        io.execute {
            try {
                val guard = SafetyGuard(this)
                val pm = packageManager
                val items = pm.getInstalledApplications(0).map { ai ->
                    AppItem(
                        pkg = ai.packageName,
                        label = ai.loadLabel(pm).toString(),
                        icon = try { ai.loadIcon(pm) } catch (e: Exception) { null },
                        verdict = guard.verdict(ai.packageName),
                        enabled = ai.enabled
                    )
                }.sortedBy { it.label.lowercase() }
                ui.post {
                    if (!isDestroyed) {
                        all = items
                        applyFilter()
                    }
                }
            } catch (e: Exception) {
                ui.post { status.text = "Could not read the app list: ${e.message}" }
            }
        }
    }

    private fun applyFilter() {
        val shown = all.filter {
            when (filter) {
                Filter.USER -> it.verdict.level == Level.USER
                Filter.SYSTEM -> it.verdict.level == Level.SYSTEM_CAUTION
                Filter.PROTECTED -> it.verdict.level == Level.PROTECTED
                Filter.ALL -> true
            }
        }
        adapter.set(shown)
        for ((f, b) in buttons) b.text = (if (f == filter) "● " else "") + f.label
        status.text = if (shown.isEmpty()) "No apps in this group" else "${shown.size} apps · ${filter.label}"
        if (shown.isNotEmpty() && !list.hasFocus() && currentFocus !is Button) list.requestFocus()
    }

    // ------------------------------------------------------------------ dialogs

    private fun showDetails(item: AppItem) {
        val state = if (item.enabled) "" else "\n(currently disabled)"
        val b = AlertDialog.Builder(this).setTitle(item.label)
        when (item.verdict.level) {
            Level.PROTECTED -> {
                b.setMessage(
                    "${item.pkg}$state\n\nLOCKED: ${item.verdict.reason}.\n\n" +
                        "The TV needs this to work properly, so removing it is disabled."
                )
                b.setPositiveButton("Close", null)
            }
            Level.USER -> {
                b.setMessage("${item.pkg}$state\n\n${item.verdict.reason}.")
                b.setPositiveButton("Uninstall") { _, _ -> confirmUninstall(item) }
                b.setNegativeButton("Cancel", null)
                b.setNeutralButton("App info") { _, _ -> openAppInfo(item.pkg) }
            }
            Level.SYSTEM_CAUTION -> {
                b.setMessage(
                    "${item.pkg}$state\n\n${item.verdict.reason}.\n\n" +
                        "System apps can't be uninstalled from a normal app. Use App info to " +
                        "disable it (reversible), or the ADB command to remove it for this user " +
                        "(also reversible)."
                )
                b.setPositiveButton("Disable (App info)") { _, _ -> openAppInfo(item.pkg) }
                b.setNegativeButton("Cancel", null)
                b.setNeutralButton("ADB command") { _, _ -> showAdb(item) }
            }
        }
        val d = b.show()
        // Safest default focus: the harmless button.
        (d.getButton(DialogInterface.BUTTON_NEGATIVE) ?: d.getButton(DialogInterface.BUTTON_POSITIVE))
            ?.requestFocus()
    }

    private fun confirmUninstall(item: AppItem) {
        val d = AlertDialog.Builder(this)
            .setTitle("Uninstall ${item.label}?")
            .setMessage("This removes the app and its data.")
            .setPositiveButton("Uninstall") { _, _ -> uninstall(item) }
            .setNegativeButton("Cancel", null)
            .show()
        d.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun showAdb(item: AppItem) {
        val remove = SafetyGuard.adbRemoveCommand(item.pkg)
        val restore = SafetyGuard.adbRestoreCommand(item.pkg)
        val d = AlertDialog.Builder(this)
            .setTitle("Remove via ADB")
            .setMessage(
                "Enable Developer options > USB/Network debugging on the TV, then from a " +
                    "computer on the same network:\n\nadb connect <TV-IP>:5555\n\nRemove:\n$remove\n\n" +
                    "Undo (restore):\n$restore"
            )
            .setPositiveButton("Copy remove cmd") { _, _ -> copy(remove) }
            .setNegativeButton("Close", null)
            .setNeutralButton("Copy restore cmd") { _, _ -> copy(restore) }
            .show()
        d.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
    }

    // ------------------------------------------------------------------ actions

    private fun uninstall(item: AppItem) {
        // Defence in depth: re-check right before acting, with fresh device state.
        val v = SafetyGuard(this).verdict(item.pkg)
        if (v.level != Level.USER) {
            toast("Blocked: ${v.reason}")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.pkg}")))
        } catch (e: ActivityNotFoundException) {
            toast("No package installer found on this TV")
        } catch (e: SecurityException) {
            toast("Uninstall not permitted")
        }
    }

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            )
        } catch (e: Exception) {
            toast("This TV has no App info screen. Use the ADB command instead.")
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", text))
        toast("Copied")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ adapter

    private inner class AppAdapter : BaseAdapter() {
        private var data: List<AppItem> = emptyList()

        fun set(items: List<AppItem>) {
            data = items
            notifyDataSetChanged()
        }

        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val item = data[position]
            v.findViewById<ImageView>(R.id.icon).setImageDrawable(item.icon)
            v.findViewById<TextView>(R.id.name).text = item.label + if (item.enabled) "" else "  (disabled)"
            v.findViewById<TextView>(R.id.pkg).text = item.pkg
            val tag = v.findViewById<TextView>(R.id.tag)
            when (item.verdict.level) {
                Level.USER -> { tag.text = "Removable"; tag.setTextColor(Color.parseColor("#81C784")) }
                Level.SYSTEM_CAUTION -> { tag.text = "System · caution"; tag.setTextColor(Color.parseColor("#FFB74D")) }
                Level.PROTECTED -> { tag.text = "🔒 Protected"; tag.setTextColor(Color.parseColor("#E57373")) }
            }
            return v
        }
    }
}
