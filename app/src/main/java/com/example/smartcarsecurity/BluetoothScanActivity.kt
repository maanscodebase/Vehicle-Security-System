package com.example.smartcarsecurity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class BluetoothScanActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var listView: ListView
    private lateinit var btnScan: Button
    private val discoveredDevices = mutableListOf<String>()           // we show MACs in the list
    private val deviceMap = hashMapOf<String, String>()               // MAC -> Name

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION // still needed for device names visibility
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    private val requestCodeBluetooth = 101
    private var isReceiverRegistered = false

    // ✅ Register for activity result from AddVehicleActivity
    private val addVehicleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Forward result back to MainActivity and close this screen
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_scan)

        initializeViews()
        setupBluetoothAdapter()
        setupListView()
        setupScanButton()
    }

    private fun initializeViews() {
        listView = findViewById(R.id.lvBluetoothDevices)
        btnScan = findViewById(R.id.btnScanBluetooth)
    }

    private fun setupBluetoothAdapter() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    private fun setupListView() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, discoveredDevices)
        listView.adapter = adapter

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (checkPermissions()) {
                val deviceMac = discoveredDevices[position]
                val deviceName = deviceMap[deviceMac] ?: "Unnamed"

                val device = bluetoothAdapter.getRemoteDevice(deviceMac)
                if (device != null) {
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        // 🔑 Trigger pairing if not already paired
                        try {
                            val paired = device.createBond()
                            if (paired) {
                                Toast.makeText(this, "Pairing with $deviceName...", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Pairing failed!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(this, "Error while pairing!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Already paired → move to AddVehicleActivity
                        val intent = Intent(this, AddVehicleActivity::class.java).apply {
                            putExtra("DEVICE_NAME", deviceName)
                            putExtra("DEVICE_ADDRESS", deviceMac)
                        }
                        addVehicleLauncher.launch(intent)
                    }
                }
            } else {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupScanButton() {
        btnScan.setOnClickListener {
            if (checkPermissions()) {
                startDiscovery()
            } else {
                requestPermissions()
            }
        }
    }

    private fun checkPermissions(): Boolean =
        bluetoothPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, bluetoothPermissions, requestCodeBluetooth)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        if (!checkPermissions()) {
            Toast.makeText(this, "Required Bluetooth permissions denied", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }

        // Fresh list
        discoveredDevices.clear()
        deviceMap.clear()
        (listView.adapter as ArrayAdapter<*>).notifyDataSetChanged()

        // Register receiver once
        if (!isReceiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            registerReceiver(bluetoothReceiver, filter)
            isReceiverRegistered = true
        }

        try {
            // If already discovering, restart
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Discovery failed due to permission", Toast.LENGTH_SHORT).show()
        }
    }

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                    device?.let { dev ->
                        if (!deviceMap.containsKey(dev.address)) {
                            val hasConnect =
                                ContextCompat.checkSelfPermission(
                                    this@BluetoothScanActivity,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED

                            val deviceName = if (hasConnect) dev.name ?: "Unknown Device" else "Unknown Device"

                            deviceMap[dev.address] = deviceName
                            discoveredDevices.add(dev.address)
                            (listView.adapter as ArrayAdapter<*>).notifyDataSetChanged()
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(this@BluetoothScanActivity, "Scan complete", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodeBluetooth && grantResults.isNotEmpty()) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startDiscovery()
            } else {
                if (askedDontAskAgain(permissions, grantResults)) {
                    showGoToSettingsDialog()
                } else {
                    Toast.makeText(this, "Permissions required for Bluetooth scanning", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun askedDontAskAgain(permissions: Array<String>, grantResults: IntArray): Boolean {
        return permissions.indices.any { i ->
            grantResults[i] != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])
        }
    }

    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission needed")
            .setMessage("Bluetooth permissions are permanently denied. Open App Settings to enable them.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(bluetoothReceiver)
                isReceiverRegistered = false
            }
            if (checkPermissions() && bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (_: Exception) {
        }
    }
}
