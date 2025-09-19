package com.example.VehicleSecuritySystem

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.VehicleSecuritySystem.databinding.ActivityProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.util.Patterns

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadUserProfile()
        loadConnectedVehicles()

        binding.btnEditProfile.setOnClickListener {
            toggleEditMode(true)
        }

        binding.btnUpdateProfile.setOnClickListener {
            saveProfile()
        }
    }

    private fun loadUserProfile() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            db.collection("users").document(userId).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val name = document.getString("name") ?: "N/A"
                        val email = document.getString("email") ?: "N/A"
                        val phone = document.getString("phone") ?: "N/A"

                        binding.tvProfileName.text = "Name: $name"
                        binding.tvProfileEmail.text = "Email: $email"
                        binding.tvProfilePhone.text = "Phone: $phone"
                        binding.etProfileName.setText(name)
                        binding.etProfilePhone.setText(phone)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to load user data.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun saveProfile() {
        val userId = auth.currentUser?.uid
        val name = binding.etProfileName.text.toString().trim()
        val phone = binding.etProfilePhone.text.toString().trim()

        if (userId == null) {
            Toast.makeText(this, "User not authenticated.", Toast.LENGTH_SHORT).show()
            return
        }

        if (name.isEmpty()) {
            binding.etProfileName.error = "Name cannot be empty"
            return
        }

        val phonePattern = Regex("^(03[0-9]{9}|\\+923[0-9]{9})$")
        if (!phonePattern.matches(phone)) {
            binding.etProfilePhone.error = "Enter valid phone number"
            return
        }

        val updates = hashMapOf<String, Any>(
            "name" to name,
            "phone" to phone
        )

        db.collection("users").document(userId).update(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                toggleEditMode(false) // Switch back to view mode
                loadUserProfile()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error updating profile.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadConnectedVehicles() {
        val sharedPref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val deviceName = sharedPref.getString("pairedDeviceName", "N/A")
        val deviceMac = sharedPref.getString("pairedDeviceMac", "N/A")


    }

    private fun toggleEditMode(inEditMode: Boolean) {
        if (inEditMode) {
            binding.profileViewLayout.visibility = View.GONE
            binding.profileEditLayout.visibility = View.VISIBLE
            binding.btnEditProfile.visibility = View.GONE
            binding.btnUpdateProfile.visibility = View.VISIBLE
        } else {
            binding.profileViewLayout.visibility = View.VISIBLE
            binding.profileEditLayout.visibility = View.GONE
            binding.btnEditProfile.visibility = View.VISIBLE
            binding.btnUpdateProfile.visibility = View.GONE
        }
    }
}