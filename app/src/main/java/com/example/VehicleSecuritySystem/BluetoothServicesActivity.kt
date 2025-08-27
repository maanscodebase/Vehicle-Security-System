package com.example.VehicleSecuritySystem

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothServicesActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnEngine: ImageButton
    private lateinit var btnLocation: ImageButton
    private lateinit var tvBluetoothStatus: TextView
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isUnlocked = false
    private val requestPermissionCode = 102
    private var deviceAddress: String? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var sharedPref: SharedPreferences
    private lateinit var handler: Handler

    companion object {
        val COMMAND_ON: Byte = '1'.code.toByte()
        val COMMAND_OFF: Byte = '0'.code.toByte()
        val COMMAND_LOCATION: Byte = 'L'.code.toByte()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_services)

        initializeViews()

        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        if (deviceAddress.isNullOrEmpty()) {
            Toast.makeText(this, "No device address provided!", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        handler = Handler(Looper.getMainLooper())
        sharedPref = getSharedPreferences("CarLocation", Context.MODE_PRIVATE)

        updateUIState("Waiting for connection...")

        if (checkPermissions()) {
            connectToDevice()
        }
    }

    private fun initializeViews() {
        tvStatus = findViewById(R.id.tvEngineAccess)
        btnEngine = findViewById(R.id.btnEngineAccess)
        btnLocation = findViewById(R.id.btnLocation)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
    }

    // 🔹 Updates status TextView with connection/command info
    private fun updateUIState(message: String? = null) {
        if (!::tvStatus.isInitialized || !::btnEngine.isInitialized) return
        tvStatus.text = if (isUnlocked) "ENGINE ON" else "ENGINE OFF"
        btnEngine.setImageResource(if (isUnlocked) R.drawable.button_power_on else R.drawable.button_power_off)
        message?.let {
            if (::tvBluetoothStatus.isInitialized) {
                tvBluetoothStatus.text = it
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect) permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasScan) permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasFineLocation) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            if (!hasFineLocation) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), requestPermissionCode)
            false
        } else true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionCode) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) connectToDevice()
            else {
                Toast.makeText(this, "Bluetooth permissions are required to connect", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice() {
        executor.execute {
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val bluetoothAdapter = bluetoothManager.adapter

                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                    runOnUiThread {
                        Toast.makeText(this, "Bluetooth is not available or not enabled", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@execute
                }

                val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress!!)
                val BLUETOOTH_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

                socket = try {
                    device.createRfcommSocketToServiceRecord(BLUETOOTH_SPP_UUID)
                } catch (e: Exception) {
                    val createMethod = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    createMethod.invoke(device, 1) as BluetoothSocket
                }

                bluetoothAdapter.cancelDiscovery()
                socket?.connect()

                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                runOnUiThread {
                    updateUIState("✅ Connected to ${device.name}")
                    startListeningForMessages()
                    setupControls()
                    sendCommand(COMMAND_OFF)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    updateUIState("❌ Connection failed: ${e.message}")
                    Log.e("BluetoothServices", "Connection failed", e)
                    Toast.makeText(this@BluetoothServicesActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun startListeningForMessages() {
        executor.execute {
            val buffer = ByteArray(1024)
            var bytes: Int

            while (true) {
                try {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val message = String(buffer, 0, bytes).trim()
                        runOnUiThread {
                            updateUIState("Arduino: $message")
                            when {
                                message.contains("Ignition ON", ignoreCase = true) -> {
                                    isUnlocked = true
                                    updateUIState()
                                }
                                message.contains("Ignition OFF", ignoreCase = true) -> {
                                    isUnlocked = false
                                    updateUIState()
                                }
                                message.startsWith("LOCATION:") -> {
                                    parseAndStoreLocation(message)
                                    Toast.makeText(this@BluetoothServicesActivity, "Location received!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        updateUIState("⚠️ Connection lost")
                        Toast.makeText(this@BluetoothServicesActivity, "Connection lost", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
            }
        }
    }

    private fun setupControls() {
        btnEngine.setOnClickListener {
            authenticateAndToggleEngine()
        }
        btnLocation.setOnClickListener {
            if (sendCommand(COMMAND_LOCATION)) {
                updateUIState("Requesting location...")
                handler.postDelayed({ openMapWithLocation() }, 2000)
            }
        }
    }

    // 🔹 Fingerprint Authentication for Engine Start
    private fun authenticateAndToggleEngine() {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        toggleEngineState()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Toast.makeText(applicationContext, "Auth error: $errString", Toast.LENGTH_SHORT).show()
                    }

                    override fun onAuthenticationFailed() {
                        Toast.makeText(applicationContext, "Auth failed", Toast.LENGTH_SHORT).show()
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify Identity")
                .setSubtitle("Fingerprint required to start engine")
                .setNegativeButtonText("Cancel")
                .build()

            biometricPrompt.authenticate(promptInfo)
        } else {
            // fallback: toggle without fingerprint if unavailable
            toggleEngineState()
        }
    }

    private fun toggleEngineState() {
        val command = if (isUnlocked) COMMAND_OFF else COMMAND_ON
        if (sendCommand(command)) {
            Toast.makeText(this, "Command sent: ${if (isUnlocked) "OFF" else "ON"}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to send command", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCommand(command: Byte): Boolean {
        return try {
            outputStream?.write(command.toInt())
            outputStream?.flush()
            Log.d("BluetoothServices", "Sent: ${Char(command.toInt())}")
            true
        } catch (e: Exception) {
            Log.e("BluetoothServices", "Error sending command", e)
            updateUIState("⚠️ Failed to send command")
            false
        }
    }

    private fun parseAndStoreLocation(locationMessage: String) {
        try {
            val coordinates = locationMessage.removePrefix("LOCATION:").split(",")
            if (coordinates.size == 2) {
                val latitude = coordinates[0].toDouble()
                val longitude = coordinates[1].toDouble()
                with(sharedPref.edit()) {
                    putString("latitude", latitude.toString())
                    putString("longitude", longitude.toString())
                    apply()
                }
                Log.d("BluetoothServices", "Stored location: $latitude, $longitude")
            }
        } catch (e: Exception) {
            Log.e("BluetoothServices", "Error parsing location", e)
            Toast.makeText(this, "Error parsing location data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMapWithLocation() {
        val latitudeStr = sharedPref.getString("latitude", null)
        val longitudeStr = sharedPref.getString("longitude", null)

        val intent = Intent(this, MapsActivity::class.java).apply {
            if (latitudeStr != null && longitudeStr != null) {
                putExtra("latitude", latitudeStr.toDouble())
                putExtra("longitude", longitudeStr.toDouble())
            } else {
                putExtra("latitude", 31.5204)
                putExtra("longitude", 74.3587)
                Toast.makeText(this@BluetoothServicesActivity, "Using demo location - no GPS fix yet", Toast.LENGTH_SHORT).show()
            }
        }
        startActivity(intent)
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            outputStream?.let { sendCommand(COMMAND_OFF) }
            outputStream?.close()
            inputStream?.close()
            socket?.close()
            executor.shutdown()
        } catch (e: IOException) {
            Log.e("BluetoothServices", "Error closing connection", e)
        }
    }
}