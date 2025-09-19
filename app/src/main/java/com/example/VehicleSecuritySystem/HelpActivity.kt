package com.example.VehicleSecuritySystem

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.VehicleSecuritySystem.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Help & Support"
        // The previous code for the button click listener has been removed as the UI no longer has a form.
    }
}
