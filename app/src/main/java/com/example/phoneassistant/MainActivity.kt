package com.example.phoneassistant

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView
    private var cachedJson: String = "{}"
    private val createDocument = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }
        scroll.addView(inner)

        inner.addView(TextView(this).apply {
            text = "Phone Assistant"
            textSize = 26f
        })
        inner.addView(TextView(this).apply {
            text = "Локальная диагностика Android. Никакие данные не отправляются автоматически."
            textSize = 14f
            setPadding(0, 12, 0, 18)
        })
        status = TextView(this).apply { textSize = 14f }
        inner.addView(status)

        addButton(inner, "Разрешить статистику использования") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addButton(inner, "Собрать диагностический отчёт") {
            buildReport()
        }
        addButton(inner, "Экспортировать отчёт (после проверки)") {
            if (cachedJson == "{}") buildReport()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "phone-assistant-report.json")
            }
            startActivityForResult(intent, createDocument)
        }
        addButton(inner, "Открыть настройки батареи") {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
        addButton(inner, "Открыть настройки использования данных") {
            startActivity(Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
        }
        addButton(inner, "Список приложений Android") {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
        }

        report = TextView(this).apply {
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }
        inner.addView(report)
        content.addView(scroll)
        setContentView(content)
        updatePermissionStatus()
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updatePermissionStatus()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updatePermissionStatus() {
        status.text = if (hasUsageAccess()) {
            "Доступ к статистике использования: разрешён."
        } else {
            "Доступ к статистике использования: пока не разрешён. Его можно включить в системных настройках."
        }
    }

    private fun buildReport() {
        val now = System.currentTimeMillis()
        val start = now - 7L * 24 * 60 * 60 * 1000
        val apps = JSONArray()
        val usageManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageAvailable = hasUsageAccess()
        val stats: Map<String, UsageStats> =
            if (usageAvailable) usageManager.queryAndAggregateUsageStats(start, now) else emptyMap()

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)
        val seen = mutableSetOf<String>()

        for (entry in resolved) {
            val packageName = entry.activityInfo.packageName
            if (!seen.add(packageName)) continue
            val appInfo = try { packageManager.getApplicationInfo(packageName, 0) } catch (_: Exception) { continue }
            val label = packageManager.getApplicationLabel(appInfo).toString()
            val usedMs = stats[packageName]?.totalTimeInForeground ?: 0L
            apps.put(JSONObject().apply {
                put("app_name", label)
                put("package_name", packageName)
                put("foreground_minutes_last_7_days", usedMs / 60000)
            })
        }

        val root = JSONObject().apply {
            put("report_type", "phone_assistant_local_diagnostics")
            put("app_version", "0.1.0")
            put("generated_at", DateFormat.getDateTimeInstance().format(Date(now)))
            put("period_days", 7)
            put("usage_access_granted", usageAvailable)
            put("launcher_apps", apps)
            put("limitations", JSONArray().put("Per-app battery and mobile-data totals are not exposed to ordinary third-party apps on standard Android."))
            put("privacy", "No network transmission. User explicitly exports this file.")
        }
        cachedJson = root.toString(2)
        report.text = "Отчёт подготовлен. Найдено приложений с ярлыком запуска: ${apps.length()}.\n" +
            "Статистика использования: ${if (usageAvailable) "доступна" else "недоступна — включи разрешение выше"}.\n\n" +
            "Предпросмотр:\n" + cachedJson.take(3500)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == createDocument && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(cachedJson.toByteArray(Charsets.UTF_8)) }
                report.text = "Отчёт сохранён в выбранное тобой место. Передача данных не выполнялась."
            } catch (e: Exception) {
                report.text = "Не удалось сохранить отчёт: ${e.message}"
            }
        }
    }
}
