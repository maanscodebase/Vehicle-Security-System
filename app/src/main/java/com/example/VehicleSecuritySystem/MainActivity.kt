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
import android.util.Log
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
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvPlaceholder: TextView
    private val vehiclesList = mutableListOf<Vehicle>()
    private val permissionRequestCode = 101
    private lateinit var executor: Executor
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var pendingDeviceAddress: String? = null
    private var lastClickedPosition: Int? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Ensure the user is authenticated before proceeding
        auth.currentUser?.uid?.let {
            userId = it
        } ?: run {
            forceLogout("User not authenticated.")
            return
        }

        initializeViews()
        setupClickListeners()
        loadSavedDevices() // This will now load from Firestore

        executor = ContextCompat.getMainExecutor(this)

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, filter)

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

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            lastClickedPosition = position
            if (hasBluetoothPermissions()) {
                handleDeviceClick(position)
            } else {
                requestBluetoothPermissions()
            }
        }

        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
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

    // Your existing biometric authentication function. It is already correctly
    // configured for the hybrid (fingerprint + PIN/password) dialog.
    private fun authenticateAndRun(action: () -> Unit) {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            Toast.makeText(this, "Biometric authentication not available", Toast.LENGTH_SHORT).show()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authentication Required")
            .setDescription("Confirm your identity to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
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
        val vehicle = vehiclesList.getOrNull(position) ?: return
        val input = EditText(this).apply { setText(vehicle.name) }

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
        val vehicle = vehiclesList.getOrNull(position) ?: return
        vehicle.id?.let { docId ->
            db.collection("users").document(userId)
                .collection("vehicles").document(docId)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(this, "Vehicle deleted", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.w("MainActivity", "Error deleting document", e)
                    Toast.makeText(this, "Error deleting vehicle", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun updateVehicleName(position: Int, newName: String) {
        if (newName.isBlank()) {
            Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        val vehicle = vehiclesList.getOrNull(position) ?: return
        vehicle.id?.let { docId ->
            db.collection("users").document(userId)
                .collection("vehicles").document(docId)
                .update("name", newName)
                .addOnSuccessListener {
                    Toast.makeText(this, "Vehicle renamed", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.w("MainActivity", "Error updating document", e)
                    Toast.makeText(this, "Error renaming vehicle", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun loadSavedDevices() {
        val userVehiclesRef = db.collection("users").document(userId).collection("vehicles")

        // Use a real-time listener to automatically update the UI when data changes
        userVehiclesRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("MainActivity", "Listen failed.", e)
                Toast.makeText(this, "Failed to load vehicles.", Toast.LENGTH_SHORT).show()
                return@addSnapshotListener
            }

            if (snapshot != null) {
                vehiclesList.clear()
                for (doc in snapshot.documents) {
                    val vehicle = doc.toObject(Vehicle::class.java)
                    if (vehicle != null) {
                        vehiclesList.add(vehicle)
                    }
                }
                listView.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    vehiclesList.map { it.name }
                )
                tvPlaceholder.visibility = if (vehiclesList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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

        val vehicle = vehiclesList.getOrNull(position)
        if (vehicle == null || vehicle.macAddress.isEmpty()) {
            Toast.makeText(this, "Invalid device entry", Toast.LENGTH_SHORT).show()
            return
        }

        val macRegex = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\$")
        if (!macRegex.matches(vehicle.macAddress)) {
            Toast.makeText(this, "Saved address looks invalid", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            navigateToBluetoothServices(vehicle.name, vehicle.macAddress)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open device: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToBluetoothServices(deviceName: String, deviceAddress: String) {
        val intent = Intent(this, BluetoothServicesActivity::class.java).apply {
            putExtra("DEVICE_NAME", deviceName)
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
                    if (hasBluetoothPermissions()) {
                        navigateToBluetoothServices(device.name ?: "Unknown Device", device.address)
                    } else {
                        requestBluetoothPermissions()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // No need to call loadSavedDevices here since the snapshot listener handles updates
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

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
            R.id.action_about -> {
                val intent = Intent(this, AboutActivity::class.java)
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
            FirebaseAuth.getInstance().signOut()
            val sharedPref = getSharedPreferences("SmartCarPrefs", MODE_PRIVATE)
            sharedPref.edit().clear().apply()
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
