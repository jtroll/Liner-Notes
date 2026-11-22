package com.trollman.linernotes

import android.app.Application
import com.google.firebase.FirebaseApp

class LinerNotesApp : Application() {

    /** Everything the screens share, made once per process. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Firebase starts itself from google-services.json before this runs. A build made before
        // the Firebase project exists has no config, so there is no app, and everything that
        // needs one stays off rather than crashing (docs/SETUP.md).
        val firebaseReady = BuildConfig.HAS_FIREBASE_CONFIG && FirebaseApp.getApps(this).isNotEmpty()
        graph = AppGraph(this, firebaseReady)
    }
}
