package com.example.VehicleSecuritySystem

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Show splash screen for 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            val auth = FirebaseAuth.getInstance()

            // Check if the user is logged in locally, without needing an internet connection.
            if (auth.currentUser != null) {
                // User is already logged in, go to Main
                startActivity(Intent(this, MainActivity::class.java))
            } else {
                // No user found, go to Login
                startActivity(Intent(this, LoginActivity::class.java))
            }
            finish()
        }, 3000)
    }
}