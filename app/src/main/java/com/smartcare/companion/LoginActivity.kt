package com.smartcare.companion

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etServer: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val TAG = "LOGIN"
        const val PREFS_NAME = "smartcare_prefs"
        const val KEY_JWT_TOKEN = "jwt_token"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_BACKEND_URL = "backend_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already logged in with a valid token, skip to MainActivity
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existingToken = prefs.getString(KEY_JWT_TOKEN, null)
        if (!existingToken.isNullOrBlank()) {
            navigateToMain()
            return
        }

        setContentView(R.layout.activity_login)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etServer = findViewById(R.id.etServer)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)

        // Restore last-used server URL
        val savedUrl = prefs.getString(KEY_BACKEND_URL, null)
        if (!savedUrl.isNullOrBlank()) {
            etServer.setText(savedUrl)
        }

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val serverUrl = etServer.text.toString().trim().trimEnd('/')

            // Basic validation
            if (email.isBlank() || password.isBlank()) {
                showError("Please enter email and password")
                return@setOnClickListener
            }
            if (serverUrl.isBlank()) {
                showError("Please enter the backend server URL")
                return@setOnClickListener
            }

            performLogin(email, password, serverUrl)
        }
    }

    private fun performLogin(email: String, password: String, serverUrl: String) {
        setLoading(true)
        hideError()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/auth/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true

                val jsonBody = JSONObject()
                jsonBody.put("email", email)
                jsonBody.put("password", password)

                val os = OutputStreamWriter(conn.outputStream)
                os.write(jsonBody.toString())
                os.flush()
                os.close()

                val responseCode = conn.responseCode

                // Read response body
                val reader = BufferedReader(
                    InputStreamReader(
                        if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    )
                )
                val responseBody = reader.readText()
                reader.close()

                val responseJson = JSONObject(responseBody)

                withContext(Dispatchers.Main) {
                    when (responseCode) {
                        200 -> {
                            // Success — save token and user info
                            val token = responseJson.getString("token")
                            val name = responseJson.optString("name", "User")
                            val userEmail = responseJson.optString("email", email)
                            val role = responseJson.optString("role", "PATIENT")

                            // Only allow PATIENT role to use companion app
                            if (role != "PATIENT") {
                                showError("Only patient accounts can use this app")
                                setLoading(false)
                                return@withContext
                            }

                            // Save to SharedPreferences
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            prefs.edit()
                                .putString(KEY_JWT_TOKEN, token)
                                .putString(KEY_USER_NAME, name)
                                .putString(KEY_USER_EMAIL, userEmail)
                                .putString(KEY_BACKEND_URL, serverUrl)
                                .apply()

                            Toast.makeText(this@LoginActivity, "Welcome, $name!", Toast.LENGTH_SHORT).show()
                            navigateToMain()
                        }
                        401 -> {
                            showError("Invalid email or password")
                            setLoading(false)
                        }
                        403 -> {
                            val errorMsg = responseJson.optString("error", "Account not verified")
                            showError(errorMsg)
                            setLoading(false)
                        }
                        else -> {
                            val errorMsg = responseJson.optString("error", "Login failed (code $responseCode)")
                            showError(errorMsg)
                            setLoading(false)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                withContext(Dispatchers.Main) {
                    showError("Cannot reach server: ${e.message}")
                    setLoading(false)
                }
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        tvError.visibility = View.GONE
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
        btnLogin.text = if (loading) "Signing in…" else "Sign In"
    }
}
