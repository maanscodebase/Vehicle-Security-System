package com.example.VehicleSecuritySystem

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.VehicleSecuritySystem.databinding.ActivitySettingsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var appId: String

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "Settings"

        // Initialize Firebase and local storage
        // These lines are critical to resolve the errors.
        auth = Firebase.auth
        db = Firebase.firestore
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // Use a default app ID if the environment variable is not available
        val appIdString = resources.getString(R.string.app_id)
        appId = if (appIdString.isNotEmpty()) appIdString else "default-app-id"

        // Load settings from SharedPreferences on startup
        loadLocalSettings()

        // Listen for authentication state changes and load settings from Firestore
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                loadFirebaseSettings(user.uid)
            }
        }

        // --- BiometricPrompt setup for fingerprint recognition ---
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    saveFingerprintStatus(true)
                    Toast.makeText(applicationContext, "Fingerprint registered successfully!", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Fingerprint Registration")
            .setSubtitle("Place your finger on the sensor to register.")
            .setNegativeButtonText("Cancel")
            .build()


        // Handle Switch states
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            saveSettings(isChecked, binding.switchDarkMode.isChecked, sharedPreferences.getString("pin", null), sharedPreferences.getBoolean("fingerprint", false))
            Toast.makeText(this, "Notifications ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            saveSettings(binding.switchNotifications.isChecked, isChecked, sharedPreferences.getString("pin", null), sharedPreferences.getBoolean("fingerprint", false))
            Toast.makeText(this, "Dark Mode ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        // --- Handle button clicks for security features ---
        binding.btnRegisterPin.setOnClickListener {
            showPinDialog(false)
        }

        binding.btnChangePin.setOnClickListener {
            showPinDialog(true)
        }

        binding.btnRegisterFingerprint.setOnClickListener {
            handleBiometricRegistration()
        }

        binding.btnChangeFingerprint.setOnClickListener {
            handleBiometricRegistration()
        }
    }

    private fun loadLocalSettings() {
        binding.switchNotifications.isChecked = sharedPreferences.getBoolean("notifications", false)
        binding.switchDarkMode.isChecked = sharedPreferences.getBoolean("darkMode", false)
    }

    private fun loadFirebaseSettings(userId: String) {
        val docRef = db.collection("artifacts").document(appId)
            .collection("users").document(userId)
            .collection("settings").document("preferences")

        docRef.get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val notifications = document.getBoolean("notifications") ?: false
                    val darkMode = document.getBoolean("darkMode") ?: false
                    val pinHash = document.getString("pinHash")
                    val fingerprintEnabled = document.getBoolean("fingerprintEnabled") ?: false

                    binding.switchNotifications.isChecked = notifications
                    binding.switchDarkMode.isChecked = darkMode

                    // Save security settings locally upon loading from cloud
                    with(sharedPreferences.edit()) {
                        putString("pin", pinHash)
                        putBoolean("fingerprint", fingerprintEnabled)
                        apply()
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load cloud settings", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveSettings(notificationsEnabled: Boolean, darkModeEnabled: Boolean, pinHash: String?, fingerprintEnabled: Boolean) {
        // Save to local storage
        with(sharedPreferences.edit()) {
            putBoolean("notifications", notificationsEnabled)
            putBoolean("darkMode", darkModeEnabled)
            putString("pin", pinHash)
            putBoolean("fingerprint", fingerprintEnabled)
            apply()
        }

        // Save to Firestore
        val user = auth.currentUser
        if (user != null) {
            val settingsData = hashMapOf(
                "notifications" to notificationsEnabled,
                "darkMode" to darkModeEnabled,
                "pinHash" to pinHash,
                "fingerprintEnabled" to fingerprintEnabled
            )

            val docRef = db.collection("artifacts").document(appId)
                .collection("users").document(user.uid)
                .collection("settings").document("preferences")

            docRef.set(settingsData)
                .addOnSuccessListener {
                    Toast.makeText(this, "Settings synced to cloud", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Failed to sync settings", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showPinDialog(isChange: Boolean) {
        val title = if (isChange) "Change PIN" else "Register PIN"
        val message = "Enter a new 4-digit PIN."

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "e.g., 1234"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Save") { dialog, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 4) {
                    val hashedPin = hashPin(pin)
                    savePin(hashedPin)
                    Toast.makeText(this, "PIN saved successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be exactly 4 digits.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(pin.toByteArray())
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }

    private fun savePin(hashedPin: String) {
        // Save locally
        with(sharedPreferences.edit()) {
            putString("pin", hashedPin)
            apply()
        }

        // Save to Firestore
        val user = auth.currentUser
        if (user != null) {
            val docRef = db.collection("artifacts").document(appId)
                .collection("users").document(user.uid)
                .collection("settings").document("preferences")

            docRef.update("pinHash", hashedPin)
                .addOnSuccessListener {
                    Toast.makeText(this, "PIN synced to cloud", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to sync PIN", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun handleBiometricRegistration() {
        val biometricManager = androidx.biometric.BiometricManager.from(this)
        when (biometricManager.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> {
                // The device can authenticate with biometrics.
                biometricPrompt.authenticate(promptInfo)
            }
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                Toast.makeText(this, "No biometric hardware on this device.", Toast.LENGTH_LONG).show()
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                Toast.makeText(this, "Biometric hardware is currently unavailable.", Toast.LENGTH_LONG).show()
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                Toast.makeText(this, "No fingerprints enrolled. Please register a fingerprint in your device's settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveFingerprintStatus(status: Boolean) {
        // Save locally
        with(sharedPreferences.edit()) {
            putBoolean("fingerprint", status)
            apply()
        }

        // Save to Firestore
        val user = auth.currentUser
        if (user != null) {
            val docRef = db.collection("artifacts").document(appId)
                .collection("users").document(user.uid)
                .collection("settings").document("preferences")

            docRef.update("fingerprintEnabled", status)
                .addOnSuccessListener {
                    Toast.makeText(this, "Fingerprint status synced to cloud", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to sync fingerprint status", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
