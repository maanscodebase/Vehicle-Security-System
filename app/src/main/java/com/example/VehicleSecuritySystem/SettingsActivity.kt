package com.example.VehicleSecuritySystem

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.VehicleSecuritySystem.databinding.ActivitySettingsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.nio.charset.StandardCharsets
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
        auth = Firebase.auth
        db = Firebase.firestore
        sharedPreferences = getSharedPreferences("Authentication", Context.MODE_PRIVATE)

        // Use the global environment variable for app ID
        appId = try {
            resources.getString(R.string.app_id)
        } catch (e: Exception) {
            "default-app-id"
        }

        // Load settings and update the UI on startup
        loadLocalSettings()
        updateSecurityUI()

        // --- BiometricPrompt setup for fingerprint recognition ---
        val executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    saveFingerprintStatus(true)
                    Toast.makeText(applicationContext, "Fingerprint enabled successfully!", Toast.LENGTH_SHORT).show()
                    updateSecurityUI()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed. Try again.", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // No action needed for errors, a toast is already shown
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Fingerprint Verification")
            .setSubtitle("Place your finger on the sensor to verify.")
            .setNegativeButtonText("Cancel")
            .build()


        // Handle Switch states and save to local storage and Firebase
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            saveSettings(isChecked, binding.switchDarkMode.isChecked, sharedPreferences.getString("user_pin_hash", null), sharedPreferences.getBoolean("fingerprint_enabled", false))
            Toast.makeText(this, "Notifications ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            // Update the app's theme based on the switch state
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            saveSettings(binding.switchNotifications.isChecked, isChecked, sharedPreferences.getString("user_pin_hash", null), sharedPreferences.getBoolean("fingerprint_enabled", false))
            Toast.makeText(this, "Dark Mode ${if (isChecked) "Enabled" else "Disabled"}", Toast.LENGTH_SHORT).show()
        }

        // --- Handle button clicks for security features ---
        binding.btnPinControl.setOnClickListener {
            showPinDialog()
        }

        binding.btnFingerprintControl.setOnClickListener {
            // Check if fingerprint is already enabled to determine action
            val isFingerprintEnabled = sharedPreferences.getBoolean("fingerprint_enabled", false)

            if (isFingerprintEnabled) {
                // If enabled, prompt to disable it
                AlertDialog.Builder(this)
                    .setTitle("Disable Fingerprint")
                    .setMessage("Are you sure you want to disable fingerprint authentication?")
                    .setPositiveButton("Disable") { _, _ ->
                        saveFingerprintStatus(false)
                        Toast.makeText(this, "Fingerprint disabled.", Toast.LENGTH_SHORT).show()
                        updateSecurityUI()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // If disabled, check if a PIN is registered before allowing fingerprint setup
                val pinRegistered = sharedPreferences.getString("user_pin_hash", null) != null
                if (pinRegistered) {
                    showPinVerificationDialogForBiometric()
                } else {
                    Toast.makeText(this, "Please register a PIN first.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateSecurityUI() {
        // Check if a PIN is registered
        val pinRegistered = sharedPreferences.getString("user_pin_hash", null) != null
        if (pinRegistered) {
            binding.btnPinControl.text = "Change PIN"
        } else {
            binding.btnPinControl.text = "Register PIN"
        }

        // Check if fingerprint is enabled
        val fingerprintEnabled = sharedPreferences.getBoolean("fingerprint_enabled", false)
        if (fingerprintEnabled) {
            binding.btnFingerprintControl.text = "Disable Fingerprint"
        } else {
            binding.btnFingerprintControl.text = "Enable Fingerprint"
        }

        // Also check if the device supports biometrics
        val biometricManager = BiometricManager.from(this)
        val biometricStatus = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (biometricStatus != BiometricManager.BIOMETRIC_SUCCESS) {
            binding.btnFingerprintControl.text = "Fingerprint Not Available"
            binding.btnFingerprintControl.isEnabled = false
        }
    }


    private fun loadLocalSettings() {
        binding.switchNotifications.isChecked = sharedPreferences.getBoolean("notifications", false)
        binding.switchDarkMode.isChecked = sharedPreferences.getBoolean("darkMode", false)

        // Apply the saved theme setting on app start
        if (binding.switchDarkMode.isChecked) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun saveSettings(notificationsEnabled: Boolean, darkModeEnabled: Boolean, pinHash: String?, fingerprintEnabled: Boolean) {
        // Save to local storage
        with(sharedPreferences.edit()) {
            putBoolean("notifications", notificationsEnabled)
            putBoolean("darkMode", darkModeEnabled)
            putString("user_pin_hash", pinHash)
            putBoolean("fingerprint_enabled", fingerprintEnabled)
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
                    Log.d("Firebase", "Settings synced to cloud")
                }
                .addOnFailureListener { e ->
                    Log.e("Firebase", "Failed to sync settings", e)
                }
        }
    }

    private fun showPinDialog() {
        val title = if (sharedPreferences.getString("user_pin_hash", null) != null) "Change PIN" else "Register PIN"
        val message = "Enter a new 4-digit PIN."

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "e.g., 1234"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 4) {
                    val hashedPin = hashPin(pin)
                    savePin(hashedPin)
                    savePinToFirebase(hashedPin) // Save to Firebase as well
                    Toast.makeText(this, "PIN saved successfully!", Toast.LENGTH_SHORT).show()
                    updateSecurityUI()
                } else {
                    Toast.makeText(this, "PIN must be exactly 4 digits.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPinVerificationDialogForBiometric() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Enter your PIN"

        AlertDialog.Builder(this)
            .setTitle("Verify PIN")
            .setMessage("Enter your PIN to enable fingerprint authentication.")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val enteredPin = input.text.toString().trim()
                val savedPinHash = sharedPreferences.getString("user_pin_hash", null)
                val hashedEnteredPin = hashPin(enteredPin)

                if (savedPinHash != null && hashedEnteredPin == savedPinHash) {
                    // PIN is correct, now proceed to enable fingerprint
                    handleBiometricVerification()
                } else {
                    Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(pin.toByteArray(StandardCharsets.UTF_8))
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }

    private fun savePin(hashedPin: String) {
        with(sharedPreferences.edit()) {
            putString("user_pin_hash", hashedPin)
            apply()
        }
    }

    private fun handleBiometricVerification() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                biometricPrompt.authenticate(promptInfo)
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                Toast.makeText(this, "No fingerprints enrolled. Please register a fingerprint in your device's settings.", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(this, "Biometric authentication is not available.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveFingerprintStatus(status: Boolean) {
        with(sharedPreferences.edit()) {
            putBoolean("fingerprint_enabled", status)
            apply()
        }

        // Save to Firestore - update the main settings document
        val user = auth.currentUser
        if (user != null) {
            val docRef = db.collection("artifacts").document(appId)
                .collection("users").document(user.uid)
                .collection("settings").document("preferences")

            // Get current settings and update fingerprint status
            docRef.get().addOnSuccessListener { document ->
                val currentSettings = document.data?.toMutableMap() ?: mutableMapOf<String, Any>()
                currentSettings["fingerprintEnabled"] = status

                docRef.set(currentSettings)
                    .addOnSuccessListener {
                        Log.d("Firebase", "Fingerprint status synced to cloud")
                    }
                    .addOnFailureListener { e ->
                        Log.e("Firebase", "Failed to sync fingerprint status", e)
                    }
            }.addOnFailureListener { e ->
                // If document doesn't exist, create it with current local settings
                val settingsData = hashMapOf(
                    "notifications" to sharedPreferences.getBoolean("notifications", false),
                    "darkMode" to sharedPreferences.getBoolean("darkMode", false),
                    "pinHash" to sharedPreferences.getString("user_pin_hash", null),
                    "fingerprintEnabled" to status
                )

                docRef.set(settingsData)
                    .addOnSuccessListener {
                        Log.d("Firebase", "Settings created and fingerprint status synced")
                    }
                    .addOnFailureListener { error ->
                        Log.e("Firebase", "Failed to create settings document", error)
                    }
            }
        }
    }

    // Function to save PIN to Firebase
    private fun savePinToFirebase(pinHash: String) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e("Firebase", "User not logged in, cannot save PIN to Firebase.")
            return
        }
        val docRef = db.collection("artifacts").document(appId)
            .collection("users").document(userId)
            .collection("security").document("pin")
        val data = hashMapOf("pin_hash" to pinHash)
        docRef.set(data)
            .addOnSuccessListener {
                Log.d("Firebase", "PIN hash successfully saved to Firebase!")
            }
            .addOnFailureListener { e ->
                Log.e("Firebase", "Error writing document", e)
            }
    }
}
