package com.example.VehicleSecuritySystem

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class AddVehicleActivity : AppCompatActivity() {

    private lateinit var etCarName: EditText
    private lateinit var btnPairCar: Button
    private lateinit var btnRegisterFingerprint: Button
    private lateinit var tvFpStatus: TextView

    private lateinit var executor: Executor
    private var fingerprintVerified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_vehicle)

        etCarName = findViewById(R.id.etCarName)
        btnPairCar = findViewById(R.id.btnPairCar)
        btnRegisterFingerprint = findViewById(R.id.btnRegisterFingerprint)
        tvFpStatus = findViewById(R.id.tvFingerprintStatus)

        executor = ContextCompat.getMainExecutor(this)

        // Disable Pair until fingerprint is verified
        btnPairCar.isEnabled = false

        btnRegisterFingerprint.setOnClickListener {
            if (canUseBiometrics()) {
                showFingerprintPrompt()
            }
        }

        btnPairCar.setOnClickListener {
            if (!fingerprintVerified) {
                Toast.makeText(this, "Please register fingerprint first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val customName = etCarName.text.toString().trim()
            val deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unnamed"
            val deviceAddress = intent.getStringExtra("DEVICE_ADDRESS") ?: run {
                Toast.makeText(this, "Missing device address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalName = if (customName.isEmpty()) deviceName else customName
            saveVehicle("$finalName|$deviceAddress|fingerprint=true")
        }
    }

    private fun canUseBiometrics(): Boolean {
        val manager = BiometricManager.from(this)
        return when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Toast.makeText(this, "No biometric hardware on this device", Toast.LENGTH_SHORT).show(); false
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Toast.makeText(this, "Biometric hardware unavailable", Toast.LENGTH_SHORT).show(); false
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Toast.makeText(this, "No fingerprint enrolled. Please enroll in Settings.", Toast.LENGTH_LONG).show(); false
            }
            else -> {
                Toast.makeText(this, "Biometrics not available", Toast.LENGTH_SHORT).show(); false
            }
        }
    }

    private fun showFingerprintPrompt() {
        val prompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    fingerprintVerified = true
                    tvFpStatus.text = "Fingerprint: registered"
                    btnPairCar.isEnabled = true
                    Toast.makeText(applicationContext, "Fingerprint registered!", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Error: $errString", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Fingerprint not recognized", Toast.LENGTH_SHORT).show()
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Register Fingerprint")
            .setSubtitle("Use your fingerprint to secure this vehicle")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(info)
    }

    private fun saveVehicle(entry: String) {
        val sharedPrefs = getSharedPreferences("SmartCarPrefs", Context.MODE_PRIVATE)
        val devices = sharedPrefs.getStringSet("paired_devices", mutableSetOf())!!.toMutableSet()

        val address = entry.split("|").getOrNull(1)
        if (address == null) {
            Toast.makeText(this, "Invalid entry", Toast.LENGTH_SHORT).show()
            return
        }

        if (devices.any { it.split("|").getOrNull(1) == address }) {
            Toast.makeText(this, "Device already paired!", Toast.LENGTH_SHORT).show()
            return
        }

        devices.add(entry)
        sharedPrefs.edit().putStringSet("paired_devices", devices).apply()

        // Return success to previous Activity (BluetoothScan), which should then finish() and bubble up to Main
        setResult(RESULT_OK)
        finish()
    }
}
