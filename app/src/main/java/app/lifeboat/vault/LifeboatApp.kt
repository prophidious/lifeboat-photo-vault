package app.lifeboat.vault

import android.app.Activity
import android.app.Application
import android.os.Bundle

class LifeboatApp : Application() {
    companion object {
        @Volatile var unlocked = false
        private var backgroundSince = 0L
        private const val RELOCK_MS = 3 * 60 * 1000L
    }

    private var started = 0

    override fun onCreate() {
        super.onCreate()
        Vault.wipePlain(this) // never leave decrypted temp files around
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                if (started == 1 && backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > RELOCK_MS) unlocked = false
            }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started == 0) backgroundSince = System.currentTimeMillis()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
