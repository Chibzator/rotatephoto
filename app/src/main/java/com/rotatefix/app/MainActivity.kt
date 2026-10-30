package com.rotatefix.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        column.addView(TextView(this).apply {
            text = "RotateFix"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
            text = "When a camera app opens, a round bubble appears.\n\n" +
                "• Tap it until the arrow points to the sky, then shoot.\n" +
                "• Long-press it to switch it off for a shot (✕).\n" +
                "• Drag it wherever it's out of the way.\n\n" +
                "Each new photo's orientation is corrected a second or two after it's saved. " +
                "Only the orientation tag changes, so image quality is untouched. " +
                "The bubble resets to portrait every time you open the camera."
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        button("1 · Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        button("2 · Allow access to all files (to fix photos)") {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        button("3 · Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        button("4 · Don't restrict battery (keeps it alive)") {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        button("▶  Start RotateFix") {
            if (!Settings.canDrawOverlays(this) || !Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Grant steps 1 and 2 first", Toast.LENGTH_LONG).show()
            } else {
                Prefs.setEnabled(this, true)
                startForegroundService(Intent(this, OrientService::class.java))
                status.postDelayed({ refresh() }, 400)
            }
        }
        button("■  Stop") {
            Prefs.setEnabled(this, false)
            stopService(Intent(this, OrientService::class.java))
            status.postDelayed({ refresh() }, 400)
        }

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val overlay = Settings.canDrawOverlays(this)
        val files = Environment.isExternalStorageManager()
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        status.text = "${mark(overlay)} Display over other apps\n" +
            "${mark(files)} All files access\n" +
            "${mark(notif)} Notifications\n" +
            "${mark(battery)} Battery unrestricted\n\n" +
            "Status: " + if (OrientService.running) "RUNNING" else "stopped"
    }
}
