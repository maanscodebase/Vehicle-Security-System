package com.example.VehicleSecuritySystem

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AddVehicleActivity : AppCompatActivity() {

    private lateinit var etCarName: EditText
    private lateinit var btnPairCar: Button
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_vehicle)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Ensure the user is authenticated
        auth.currentUser?.uid?.let {
            userId = it
        } ?: run {
            Toast.makeText(this, "User not authenticated.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        etCarName = findViewById(R.id.etCarName)
        btnPairCar = findViewById(R.id.btnPairCar)

        val deviceName = intent.getStringExtra("DEVICE_NAME") ?: "Unnamed Device"
        val deviceAddress = intent.getStringExtra("MAC_ADDRESS") ?: ""

        etCarName.setText(deviceName)

        btnPairCar.setOnClickListener {
            if (deviceAddress.isEmpty()) {
                Toast.makeText(this, "Missing device address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val customName = etCarName.text.toString().trim()
            val finalName = if (customName.isEmpty()) deviceName else customName
            saveVehicle(finalName, deviceAddress)
        }
    }

    private fun saveVehicle(name: String, macAddress: String) {
        val userVehiclesCollection = db.collection("users").document(userId).collection("vehicles")

        // Check if a device with the same MAC address already exists
        userVehiclesCollection
            .whereEqualTo("macAddress", macAddress)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot.isEmpty) {
                    // Device is not paired, proceed to add
                    val newVehicle = Vehicle(name = name, macAddress = macAddress)
                    userVehiclesCollection.add(newVehicle)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Vehicle added successfully", Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Log.w("AddVehicleActivity", "Error adding document", e)
                            Toast.makeText(this, "Error adding vehicle", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    // Device is already paired
                    Toast.makeText(this, "Device is already paired!", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Log.w("AddVehicleActivity", "Error checking for existing device", e)
                Toast.makeText(this, "Error checking device status", Toast.LENGTH_SHORT).show()
            }
    }
}
