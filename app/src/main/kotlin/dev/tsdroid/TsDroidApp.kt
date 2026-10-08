package dev.tsdroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log
import dev.tsdroid.han.R
import java.io.File

class TsDroidApp : Application() {

    companion object {
        const val CHANNEL_ID_CONNECTION = "ts_connection"
        const val CHANNEL_ID_POKE = "ts_poke"

        /** Activities currently started — pokes only notify when none exist. */
        private var startedActivities = 0
        val isAppForeground: Boolean get() = startedActivities > 0

        init {
            System.loadLibrary("tslib_jni")
        }
    }

    override fun onCreate() {
        // 崩溃陷阱：未捕获异常的堆栈写入 filesDir/last_crash.txt，下次启动在
        // 界面上展示并可一键复制（无 adb 环境时也能拿到闪退原因）。
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
        super.onCreate()
        createNotificationChannels()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val connection = NotificationChannel(
            CHANNEL_ID_CONNECTION,
            getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_connection_desc)
        }
        manager.createNotificationChannel(connection)

        // Own channel so the user can mute pokes (or DND rules apply to them)
        // independently of the permanent connection notification; HIGH makes
        // them heads-up like they deserve as a social attention signal
        val poke = NotificationChannel(
            CHANNEL_ID_POKE,
            getString(R.string.channel_poke),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_poke_desc)
        }
        manager.createNotificationChannel(poke)
    }
}
