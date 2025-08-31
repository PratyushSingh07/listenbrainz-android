package org.listenbrainz.android.application

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.limurse.logger.Logger
import com.limurse.logger.config.Config
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.listenbrainz.android.BuildConfig
import org.listenbrainz.android.R
import org.listenbrainz.android.repository.preferences.AppPreferences
import org.listenbrainz.android.service.ListenSubmissionService
import org.listenbrainz.android.util.Constants
import org.listenbrainz.android.util.Log
import org.listenbrainz.android.util.Utils.isServiceRunning
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        context = this
        super.onCreate()

        val logDirectory = applicationContext.getExternalFilesDir(null)?.path.orEmpty()
        val config = Config.Builder(logDirectory)
            .setDefaultTag(Constants.TAG)
            .setLogcatEnable(true)
            .setDataFormatterPattern("dd-MM-yyyy-HH:mm:ss")
            .setStartupData(collectStartupData())
            .build()

        Logger.init(config)

        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }

        GlobalScope.launch {
            startListenService(appPreferences)
        }
    }

    private fun collectStartupData(): Map<String, String> = mapOf(
        "App Version" to System.currentTimeMillis().toString(),
        "Device Application Id" to BuildConfig.APPLICATION_ID,
        "Device Version Code" to BuildConfig.VERSION_CODE.toString(),
        "Device Version Name" to BuildConfig.VERSION_NAME,
        "Device Build Type" to BuildConfig.BUILD_TYPE,
        "Device" to Build.DEVICE,
        "Device SDK" to Build.VERSION.SDK_INT.toString(),
        "Device Manufacturer" to Build.MANUFACTURER
    )

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyFlashScreen()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    companion object {
        lateinit var context: App
            private set

        suspend fun startListenService(appPreferences: AppPreferences) = withContext(Dispatchers.Main) {
            if (
                appPreferences.isNotificationServiceAllowed &&
                appPreferences.lbAccessToken.get().isNotEmpty() &&
                appPreferences.isListeningAllowed.get()
            ) {
                val intent = Intent(context, ListenSubmissionService::class.java)
                if (!context.isServiceRunning(ListenSubmissionService::class.java)) {
                    val component = runCatching {
                         context.startService(intent)
                    }.getOrElse { error ->
                        Log.d(error)
                        null
                    }

                    if (component == null) {
                        Log.d("No running instances found, starting service.")
                    } else {
                        Log.d("Service already running with name: $component")
                    }
                } else {
                    Log.d("Service already running")
                }
            }
        }
    }
}
