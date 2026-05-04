package com.smartcare.companion

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
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
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUser: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnSync: Button
    private lateinit var btnLogout: Button

    // Read from SharedPreferences (set by LoginActivity)
    private var backendUrl = ""
    private var jwtToken = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvUser = findViewById(R.id.tvUser)
        btnConnect = findViewById(R.id.btnConnect)
        btnSync = findViewById(R.id.btnSync)
        btnLogout = findViewById(R.id.btnLogout)

        // Load saved credentials
        val prefs = getSharedPreferences(LoginActivity.PREFS_NAME, MODE_PRIVATE)
        jwtToken = prefs.getString(LoginActivity.KEY_JWT_TOKEN, "") ?: ""
        backendUrl = prefs.getString(LoginActivity.KEY_BACKEND_URL, "") ?: ""
        val userName = prefs.getString(LoginActivity.KEY_USER_NAME, "User") ?: "User"
        val userEmail = prefs.getString(LoginActivity.KEY_USER_EMAIL, "") ?: ""

        // If no token, redirect to login
        if (jwtToken.isBlank()) {
            navigateToLogin()
            return
        }

        tvUser.text = "Logged in as: $userName ($userEmail)"

        btnConnect.setOnClickListener {
            checkHealthConnectAvailability()
        }

        btnSync.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                readAndSyncVitals()
            }
        }

        btnLogout.setOnClickListener {
            // Clear saved credentials
            prefs.edit().clear().apply()
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            navigateToLogin()
        }
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun checkHealthConnectAvailability() {
        val availabilityStatus = HealthConnectClient.getSdkStatus(this, "com.google.android.apps.healthdata")
        if (availabilityStatus == HealthConnectClient.SDK_AVAILABLE) {
            tvStatus.text = "Health Connect Available! Ready to sync."
        } else {
            tvStatus.text = "Health Connect is NOT installed or supported on this phone."
        }
    }

    private suspend fun readAndSyncVitals() {
        try {
            val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)
            
            // Look at the last 24 hours
            val endTime = Instant.now()
            val startTime = endTime.minus(24, ChronoUnit.HOURS)
            val timeRange = TimeRangeFilter.between(startTime, endTime)

            withContext(Dispatchers.Main) { tvStatus.text = "Reading from Health Connect..." }

            // 1. Heart Rate
            val hrRequest = ReadRecordsRequest(HeartRateRecord::class, timeRange)
            val hrRecords = healthConnectClient.readRecords(hrRequest).records
            val latestHr = hrRecords.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute?.toInt() ?: 72

            // 2. SpO2
            val spo2Request = ReadRecordsRequest(OxygenSaturationRecord::class, timeRange)
            val spo2Records = healthConnectClient.readRecords(spo2Request).records
            val latestSpo2 = spo2Records.lastOrNull()?.percentage?.value?.toInt() ?: 98

            // 3. Steps (aggregate theoretically, or grab latest daily record)
            val stepsRequest = ReadRecordsRequest(StepsRecord::class, timeRange)
            val stepsRecords = healthConnectClient.readRecords(stepsRequest).records
            val totalSteps = stepsRecords.sumOf { it.count }.toInt()

            // 4. Sleep
            val sleepRequest = ReadRecordsRequest(SleepSessionRecord::class, timeRange)
            val sleepRecords = healthConnectClient.readRecords(sleepRequest).records
            val latestSleep = sleepRecords.lastOrNull()
            val sleepMinutes = if (latestSleep != null) {
                java.time.Duration.between(latestSleep.startTime, latestSleep.endTime).toMinutes().toInt()
            } else 480 // 8 hours fallback

            withContext(Dispatchers.Main) { tvStatus.text = "Pushing to Backend..." }

            // Push to Backend
            sendToBackend(latestHr, latestSpo2, totalSteps, sleepMinutes)

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { 
                tvStatus.text = "Error: ${e.message}"
                Log.e("SYNC", "Health connect error", e)
            }
        }
    }

    private suspend fun sendToBackend(hr: Int, spo2: Int, steps: Int, sleepMinutes: Int) {
        try {
            val url = URL("$backendUrl/api/vitals/sync")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $jwtToken")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true

            // JSON Payload matching VitalsRequest.java
            val jsonBody = JSONObject()
            jsonBody.put("heartRate", hr)
            jsonBody.put("spo2", spo2)
            jsonBody.put("steps", steps)
            jsonBody.put("sleepMinutes", sleepMinutes)

            val os = OutputStreamWriter(conn.outputStream)
            os.write(jsonBody.toString())
            os.flush()
            os.close()

            val responseCode = conn.responseCode
            withContext(Dispatchers.Main) {
                when {
                    responseCode == 201 || responseCode == 200 -> {
                        tvStatus.text = "Sync Successful! ✅\nHR: $hr | SpO2: $spo2 | Steps: $steps | Sleep: ${sleepMinutes}min"
                        Toast.makeText(this@MainActivity, "Data Synced!", Toast.LENGTH_SHORT).show()
                    }
                    responseCode == 401 -> {
                        // Token expired — force re-login
                        tvStatus.text = "Session expired. Please log in again."
                        Toast.makeText(this@MainActivity, "Session expired", Toast.LENGTH_SHORT).show()
                        val prefs = getSharedPreferences(LoginActivity.PREFS_NAME, MODE_PRIVATE)
                        prefs.edit().clear().apply()
                        navigateToLogin()
                    }
                    else -> {
                        // Read error body for details
                        try {
                            val reader = BufferedReader(InputStreamReader(conn.errorStream))
                            val errorBody = reader.readText()
                            reader.close()
                            tvStatus.text = "Sync Failed ($responseCode): $errorBody"
                        } catch (e: Exception) {
                            tvStatus.text = "Sync Failed: Server returned $responseCode"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "Network Error: ${e.message}"
            }
        }
    }
}
