package com.example.VehicleSecuritySystem

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvPlaceholder: TextView
    private val pairedDevicesList = mutableListOf<String>()
    private val permissionRequestCode = 101
    private lateinit var executor: Executor
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var pendingDeviceAddress: String? = null
    private var lastClickedPosition: Int? = null

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        auth = FirebaseAuth.getInstance()

        initializeViews()
        setupClickListeners()
        loadSavedDevices()

        if (intent.getBooleanExtra("FROM_ADD_VEHICLE", false)) {
            loadSavedDevices()
        }

        executor = ContextCompat.getMainExecutor(this)

        // Register for bond-state changes
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, filter)

        // If device has no Bluetooth, inform and exit gracefully
        if (bluetoothAdapter == null) {
            AlertDialog.Builder(this)
                .setTitle("Bluetooth unavailable")
                .setMessage("This device does not support Bluetooth.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        val user = auth.currentUser
        if (user == null) {
            forceLogout("Your account has been removed. Please contact support.")
        } else {
            user.reload().addOnCompleteListener {
                if (!it.isSuccessful || !user.isEmailVerified) {
                    forceLogout("Please verify your email before using the app.")
                }
            }
        }
    }

    private fun forceLogout(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Access Restricted")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                auth.signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .show()
    }

    private fun initializeViews() {
        listView = findViewById(R.id.lvVehicles)
        tvPlaceholder = findViewById(R.id.tvPlaceholder)
    }

    private fun setupClickListeners() {
        findViewById<ImageButton>(R.id.btnAddVehicle).setOnClickListener {
            startActivity(Intent(this, BluetoothScanActivity::class.java))
        }

        listView.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                lastClickedPosition = position
                if (hasBluetoothPermissions()) {
                    handleDeviceClick(position)
                } else {
                    requestBluetoothPermissions()
                }
            }

        listView.onItemLongClickListener =
            AdapterView.OnItemLongClickListener { _, _, position, _ ->
                showOptionsDialog(position)
                true
            }
    }

    private fun showOptionsDialog(position: Int) {
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(this).apply {
            setTitle("Choose Action")
            setItems(options) { _, which ->
                when (which) {
                    0 -> authenticateAndRun { showRenameDialog(position) }
                    1 -> authenticateAndRun { deleteVehicle(position) }
                }
            }
            show()
        }
    }

    private fun authenticateAndRun(action: () -> Unit) {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            Toast.makeText(this, "Fingerprint not available", Toast.LENGTH_SHORT).show()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authentication Required")
            .setDescription("Confirm your fingerprint to continue")
            .setNegativeButtonText("Cancel")
            .build()

        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    action()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

    private fun showRenameDialog(position: Int) {
        val currentName = pairedDevicesList[position].split("|").firstOrNull().orEmpty()
        val input = EditText(this).apply { setText(currentName) }

        AlertDialog.Builder(this).apply {
            setTitle("Rename Vehicle")
            setView(input)
            setPositiveButton("Save") { _, _ ->
                updateVehicleName(position, input.text.toString())
            }
            setNegativeButton("Cancel", null)
            show()
        }
    }

    private fun deleteVehicle(position: Int) {
        val sharedPrefs = getSharedPreferences("SmartCarPrefs", Context.MODE_PRIVATE)
        val devices =
            sharedPrefs.getStringSet("paired_devices", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
        val entryToRemove = pairedDevicesList.getOrNull(position) ?: return

        with(sharedPrefs.edit()) {
            devices.remove(entryToRemove)
            putStringSet("paired_devices", devices)
            apply()
        }
        loadSavedDevices()
        Toast.makeText(this, "Vehicle deleted", Toast.LENGTH_SHORT).show()
    }

    private fun updateVehicleName(position: Int, newName: String) {
        if (newName.isBlank()) {
            Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val oldEntry = pairedDevicesList.getOrNull(position) ?: return
        val parts = oldEntry.split("|", limit = 3)
        val address = parts.getOrNull(1)?.trim().orEmpty()
        val tail = if (parts.size >= 3) "|${parts[2]}" else ""
        val newEntry = "$newName|$address$tail"

        val sharedPrefs = getSharedPreferences("SmartCarPrefs", Context.MODE_PRIVATE)
        val devices =
            sharedPrefs.getStringSet("paired_devices", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()

        with(sharedPrefs.edit()) {
            devices.remove(oldEntry)
            devices.add(newEntry)
            putStringSet("paired_devices", devices)
            apply()
        }
        loadSavedDevices()
    }

    private fun loadSavedDevices() {
        val sharedPrefs = getSharedPreferences("SmartCarPrefs", Context.MODE_PRIVATE)
        pairedDevicesList.clear()
        sharedPrefs.getStringSet("paired_devices", mutableSetOf())?.let {
            pairedDevicesList.addAll(it)
        }

        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            pairedDevicesList.map { it.split("|").firstOrNull().orEmpty() }
        )
        tvPlaceholder.visibility =
            if (pairedDevicesList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
                lastClickedPosition?.let { position ->
                    handleDeviceClick(position)
                }
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permissions are required to connect",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleDeviceClick(position: Int) {
        if (position < 0 || position >= pairedDevicesList.size) {
            Toast.makeText(this, "Invalid selection", Toast.LENGTH_SHORT).show()
            return
        }

        // Check for Bluetooth permissions before attempting to navigate
        if (hasBluetoothPermissions()) {
            val parts = pairedDevicesList[position].split("|", limit = 3)
            val deviceName = parts.getOrNull(0)?.trim()
            val deviceAddress = parts.getOrNull(1)?.trim()

            if (deviceAddress.isNullOrEmpty()) {
                Toast.makeText(this, "Invalid device entry", Toast.LENGTH_SHORT).show()
                return
            }

            val macRegex = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\$")
            if (!macRegex.matches(deviceAddress)) {
                Toast.makeText(this, "Saved address looks invalid", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                navigateToBluetoothServices(deviceName, deviceAddress)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open device: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // If permission is not granted, request it
            requestBluetoothPermissions()
        }
    }

    private fun navigateToBluetoothServices(deviceName: String?, deviceAddress: String) {
        val safeName = deviceName ?: "Unknown Device"
        val intent = Intent(this, BluetoothServicesActivity::class.java).apply {
            putExtra("DEVICE_NAME", safeName)
            putExtra("DEVICE_ADDRESS", deviceAddress)
        }
        startActivity(intent)
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

                if (device != null && bondState == BluetoothDevice.BOND_BONDED && device.address == pendingDeviceAddress) {
                    // Check for Bluetooth permissions before navigating
                    if (hasBluetoothPermissions()) {
                        navigateToBluetoothServices(device.name ?: "Unknown Device", device.address)
                    } else {
                        // If permission is not granted, request it
                        // This scenario is unlikely since the bonding process often requires it,
                        // but it's a good practice to handle it.
                        requestBluetoothPermissions()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadSavedDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver might not be registered — ignore
        }
    }

    // -------------------- MENU --------------------

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile -> {
                val intent = Intent(this, ProfileActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_help -> {
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_logout -> {
                showLogoutConfirmationDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLogoutConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Logout")
        builder.setMessage("Are you sure you want to logout?")
        builder.setPositiveButton("Yes") { dialog, _ ->
            // Firebase logout
            FirebaseAuth.getInstance().signOut()

            // Clear SharedPreferences
            val sharedPref = getSharedPreferences("SmartCarPrefs", MODE_PRIVATE)
            sharedPref.edit().clear().apply()

            // Navigate to LoginActivity and clear back stack
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            dialog.dismiss()
        }
        builder.setNegativeButton("No") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }
}