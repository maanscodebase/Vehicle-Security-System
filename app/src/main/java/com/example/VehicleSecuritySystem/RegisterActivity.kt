package com.example.VehicleSecuritySystem

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.example.VehicleSecuritySystem.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
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
            val name = binding.nameEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()
            val confirmPassword = binding.confirmPasswordEditText.text.toString().trim()

            if (!validate(email, name, password, confirmPassword)) return@setOnClickListener

            registerUserWithVerification(email, name, password)
        }

        // Navigate to login screen
        binding.loginTextView.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun registerUserWithVerification(email: String, name: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val user = authResult.user!!

                // Send verification email
                user.sendEmailVerification()
                    .addOnSuccessListener {
                        // Save user data (unverified yet)
                        saveUserToFirestore(user.uid, email, name)

                        AlertDialog.Builder(this)
                            .setTitle("Verify Your Email")
                            .setMessage("A verification email has been sent to $email. Please verify before logging in.")
                            .setPositiveButton("OK") { _, _ ->
                                auth.signOut()
                                startActivity(Intent(this, LoginActivity::class.java))
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    .addOnFailureListener {
                        // Failed to send verification → delete account
                        deleteUnverifiedUser(user)
                        binding.emailEditText.error = "Cannot send verification to this email."
                        Toast.makeText(
                            this,
                            "Email may be invalid or unreachable. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .addOnFailureListener { exception ->
                handleRegistrationFailure(exception)
            }
    }

    private fun saveUserToFirestore(userId: String, email: String, name: String) {
        val userMap = hashMapOf(
            "email" to email,
            "name" to name,
            "emailVerified" to false,
            "createdAt" to System.currentTimeMillis(),
            "status" to "active" // 🆕 Add this new field
        )

        db.collection("users").document(userId).set(userMap)
            .addOnFailureListener {
                Toast.makeText(this, "Error saving user data", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteUnverifiedUser(user: FirebaseUser) {
        user.delete().addOnFailureListener {
            // Silent fail
        }
    }

    private fun handleRegistrationFailure(exception: Exception) {
        when (exception) {
            is FirebaseAuthUserCollisionException -> {
                binding.emailEditText.error = "This Email Address Is Already Registered"
                Toast.makeText(this, "This Email Address Is Already Registered", Toast.LENGTH_SHORT).show()
            }
            is FirebaseAuthInvalidCredentialsException -> {
                binding.emailEditText.error = "Invalid email format"
                Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Registration failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun validate(email: String, name: String, password: String, confirmPassword: String): Boolean {
        // Email validation
        if (!isValidEmailFormat(email)) {
            binding.emailEditText.error = "Enter a valid email address (example@domain.com)"
            return false
        }

        // Name validation
        if (name.isEmpty()) {
            binding.nameEditText.error = "Name cannot be empty"
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

    private fun isValidEmailFormat(email: String): Boolean {
        val pattern = Patterns.EMAIL_ADDRESS
        if (!pattern.matcher(email).matches()) {
            return false
        }

        val parts = email.split("@")
        if (parts.size != 2) return false

        val domainPart = parts[1]
        if (domainPart.split(".").size < 2) return false

        return true
    }
}