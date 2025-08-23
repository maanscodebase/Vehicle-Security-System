package com.example.smartcarsecurity

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
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvPlaceholder: TextView
    private val pairedDevicesList = mutableListOf<String>()
    private val permissionRequestCode = 101
    private lateinit var executor: Executor
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var pendingDeviceAddress: String? = null
    private var lastClickedPosition: Int? = null // Store last clicked position

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        initializeViews()
        setupClickListeners()
        loadSavedDevices()

        if (intent.getBooleanExtra("FROM_ADD_VEHICLE", false)) {
            loadSavedDevices()
        }

        executor = ContextCompat.getMainExecutor(this)

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, filter)
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
                lastClickedPosition = position // Store the clicked position
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
        val currentName = pairedDevicesList[position].split("|")[0]
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
        val entryToRemove = pairedDevicesList[position]

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

        val sharedPrefs = getSharedPreferences("SmartCarPrefs", Context.MODE_PRIVATE)
        val devices =
            sharedPrefs.getStringSet("paired_devices", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
        val oldEntry = pairedDevicesList[position]
        val newEntry = "$newName|${oldEntry.split("|")[1]}"

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
            pairedDevicesList.map { it.split("|")[0] }
        )
        tvPlaceholder.visibility =
            if (pairedDevicesList.isEmpty()) TextView.VISIBLE else TextView.GONE
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
                Toast.makeText(this, "Bluetooth permissions are required to connect", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleDeviceClick(position: Int) {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        val selectedEntry = pairedDevicesList[position].split("|")
        if (selectedEntry.size < 2) {
            Toast.makeText(this, "Invalid device entry", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceAddress = selectedEntry[1]

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestBluetoothPermissions()
                    return
                }
            }

            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                try {
                    pendingDeviceAddress = deviceAddress
                    device.createBond()
                    Toast.makeText(this, "Pairing with device...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Pairing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                navigateToBluetoothServices(deviceAddress)
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Invalid device address", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied. Please grant Bluetooth permissions", Toast.LENGTH_SHORT).show()
            requestBluetoothPermissions()
        }
    }

    private fun navigateToBluetoothServices(deviceAddress: String) {
        Intent(this, BluetoothServicesActivity::class.java).apply {
            putExtra("DEVICE_ADDRESS", deviceAddress)
            startActivity(this)
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                val bondState = intent?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

                if (device != null && bondState == BluetoothDevice.BOND_BONDED && device.address == pendingDeviceAddress) {
                    Toast.makeText(applicationContext, "Paired successfully!", Toast.LENGTH_SHORT).show()
                    navigateToBluetoothServices(device.address)
                    pendingDeviceAddress = null
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
        } catch (e: IllegalArgumentException) {
            // Ignore if receiver was not registered
        }
    }

    // -------------------- LOGOUT MENU --------------------

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
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
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

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
