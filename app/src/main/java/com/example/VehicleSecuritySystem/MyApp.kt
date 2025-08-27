package com.example.VehicleSecuritySystem

import android.app.Application
import com.google.firebase.FirebaseApp

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase initialize
        FirebaseApp.initializeApp(this)
    }
}
