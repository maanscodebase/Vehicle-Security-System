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
            val user = auth.currentUser

            if (user != null) {
                user.reload().addOnSuccessListener {
                    if (user.isEmailVerified) {
                        // 🚀 Go directly to Main if already logged in & verified
                        startActivity(Intent(this, MainActivity::class.java))
                    } else {
                        // 🚪 Not verified → send to Login
                        auth.signOut()
                        startActivity(Intent(this, LoginActivity::class.java))
                    }
                    finish()
                }
            } else {
                // No user → go to Login
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }, 3000)
    }
}
