package com.example.smartcarsecurity

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smartcarsecurity.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()
    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Toggle password visibility
        binding.togglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            binding.passwordEditText.transformationMethod =
                if (isPasswordVisible) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            binding.passwordEditText.setSelection(binding.passwordEditText.text.length)
        }

        binding.toggleConfirmPassword.setOnClickListener {
            isConfirmPasswordVisible = !isConfirmPasswordVisible
            binding.confirmPasswordEditText.transformationMethod =
                if (isConfirmPasswordVisible) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            binding.confirmPasswordEditText.setSelection(binding.confirmPasswordEditText.text.length)
        }

        // Register button
        binding.registerButton.setOnClickListener {
            val email = binding.emailEditText.text.toString().trim()
            val phone = binding.phoneEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()
            val confirmPassword = binding.confirmPasswordEditText.text.toString().trim()

            if (!validate(email, phone, password, confirmPassword)) return@setOnClickListener

            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    val userId = auth.currentUser!!.uid
                    val userMap = hashMapOf(
                        "email" to email,
                        "phone" to phone,
                        "createdAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(userId).set(userMap)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Registration Successful", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, MainActivity::class.java))
                            finishAffinity()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Error saving user: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
                .addOnFailureListener { exception ->
                    if (exception is FirebaseAuthUserCollisionException) {
                        Toast.makeText(this, "User already registered", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Registration failed. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        // Navigate to login screen
        binding.loginTextView.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun validate(email: String, phone: String, password: String, confirmPassword: String): Boolean {
        // Email validation
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailEditText.error = "Enter a valid email address"
            return false
        }

        // Phone validation (Pakistan only: 03xxxxxxxxx or +923xxxxxxxxx)
        val phonePattern = Regex("^(03[0-9]{9}|\\+923[0-9]{9})$")
        if (!phonePattern.matches(phone)) {
            binding.phoneEditText.error = "Enter valid phone (03125541120 or +923125541120)"
            return false
        }

        // Password validation
        val passwordPattern = Regex("^(?=.*[A-Z])(?=.*\\d).{7,}\$")
        if (!passwordPattern.containsMatchIn(password)) {
            binding.passwordEditText.error = "Password must have 1 capital, 1 number, min 7 chars"
            return false
        }

        // Confirm password
        if (password != confirmPassword) {
            binding.confirmPasswordEditText.error = "Passwords do not match"
            return false
        }

        return true
    }
}
