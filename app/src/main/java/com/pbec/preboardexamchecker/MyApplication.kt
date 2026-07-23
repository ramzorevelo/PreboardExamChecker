package com.pbec.preboardexamchecker

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        val cacheSettings = PersistentCacheSettings.newBuilder()
            .setSizeBytes(com.google.firebase.firestore.FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            .build()
        FirebaseFirestore.getInstance().firestoreSettings =
            com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(cacheSettings)
                .build()

        Log.d("OpenCV_Loader_Check", "Core.NATIVE_LIBRARY_NAME: ${Core.NATIVE_LIBRARY_NAME}")

        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV initialization failed!")
        } else {
            Log.d("OpenCV", "OpenCV initialization succeeded.")
        }

        // Drain scan results left unsynced across process death.
        com.pbec.preboardexamchecker.data.repository.ScanResultRepository.enqueueBacklogSync(this)
    }
}
