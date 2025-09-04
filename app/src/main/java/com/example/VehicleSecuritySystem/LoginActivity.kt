package com.example.VehicleSecuritySystem

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.VehicleSecuritySystem.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Toggle password visibility
        binding.ivTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            binding.etPassword.transformationMethod =
                if (isPasswordVisible) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            binding.etPassword.setSelection(binding.etPassword.text.length)
        }

        // Forgot Password
        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
            } else {
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Reset link sent to $email", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Error sending reset link", Toast.LENGTH_LONG).show()
                    }
            }
        }

        // Login Button
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    val user = auth.currentUser
                    if (user != null) {
                        if (user.isEmailVerified) {
                            Toast.makeText(this, "Login Successful!", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, MainActivity::class.java))
                            finishAffinity()
                        } else {
                            showVerificationDialog(user)
                        }
                    } else {
                        Toast.makeText(this, "User not found. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { exception ->
                    handleLoginFailure(exception)
                }
        }

        // Navigate to Register
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    private fun showVerificationDialog(user: com.google.firebase.auth.FirebaseUser) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Email Not Verified")
            .setMessage("Please verify your email before logging in. A verification link was sent to ${user.email}.")
            .setPositiveButton("OK", null)
            .setNegativeButton("Resend Email") { _, _ ->
                user.sendEmailVerification()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Verification email resent.", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to resend email. Please try again later.", Toast.LENGTH_LONG).show()
                    }
            }
            .create()
        dialog.show()
    }

    private fun handleLoginFailure(exception: Exception) {
        when (exception) {
            is FirebaseAuthInvalidUserException -> {
                // This can happen if the email is not registered
                Toast.makeText(this, "Login Failed: Invalid credentials.", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(this, "Login Failed: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}