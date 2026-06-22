package com.youga.vehiclesafety

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley

class MainActivity : AppCompatActivity() {

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var txtStatus: TextView? = null
    private var cardSecurityStatus: CardView? = null
    private var ledOn: View? = null
    private var ledOff: View? = null
    private var etSsid: EditText? = null
    private var etPassword: EditText? = null
    private var queue: RequestQueue? = null

    private val ipEsp32 = "http://192.168.4.1"
    private lateinit var sharedPreferences: SharedPreferences

    // ===== POLLING OTOMATIS =====
    private val pollingHandler = Handler(Looper.getMainLooper())
    private val pollingIntervalMs = 1000L
    private var isWifiConnected = false
    private var isPolling = false

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (isWifiConnected) {
                checkEsp32Connection()
                pollingHandler.postDelayed(this, pollingIntervalMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        cardSecurityStatus = findViewById(R.id.cardSecurityStatus)
        ledOn = findViewById(R.id.ledOn)
        ledOff = findViewById(R.id.ledOff)
        etSsid = findViewById(R.id.etSsid)
        etPassword = findViewById(R.id.etPassword)
        queue = Volley.newRequestQueue(this)

        sharedPreferences = getSharedPreferences("FingerprintPrefs", MODE_PRIVATE)

        setMakeCircle(ledOn, Color.DKGRAY)
        setMakeCircle(ledOff, "#BA1A1A".toColorInt())

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        startWifiMonitor()

        // 1. PANEL KONTROL KENDARAAN (ON/OFF)
        findViewById<Button>(R.id.btnOnScan).setOnClickListener { sendCommand("$ipEsp32/scan_ready") }
        findViewById<Button>(R.id.btnOffTotal).setOnClickListener { sendCommand("$ipEsp32/lock") }

        // 2. PANEL KONTROL BUZZER
        findViewById<Button>(R.id.btnBuzzerOn).setOnClickListener { sendCommand("$ipEsp32/buzzer?mode=on") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { sendCommand("$ipEsp32/buzzer?mode=mute") }

        // 3. FINGERPRINT MANAGER
        findViewById<Button>(R.id.btnDaftarJari).setOnClickListener { checkAndEnrollFingerprint() }
        findViewById<Button>(R.id.btnKelolaJari).setOnClickListener { showDeleteFingerprintDialog() }

        // 4. PENGATURAN WIFI
        findViewById<Button>(R.id.btnRestartWifi).setOnClickListener {
            val ssid = etSsid?.text.toString()
            val pass = etPassword?.text.toString()
            if (ssid.isNotEmpty() && pass.length >= 8) {
                sendCommand("$ipEsp32/wifi?ssid=$ssid&pass=$pass")
            } else {
                Toast.makeText(this, "Password minimal 8 karakter!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setMakeCircle(view: View?, color: Int) {
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(color)
        view?.background = shape
    }

    private fun startWifiMonitor() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { startPolling() }
            }
            override fun onLost(network: Network) {
                runOnUiThread {
                    stopPolling()
                    setStatusTerkunci()
                }
            }
        }
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun startPolling() {
        if (isPolling) return
        isWifiConnected = true
        isPolling = true
        pollingHandler.post(pollingRunnable)
    }

    private fun stopPolling() {
        isWifiConnected = false
        isPolling = false
        pollingHandler.removeCallbacks(pollingRunnable)
    }

    private fun checkEsp32Connection() {
        val url = "$ipEsp32/status"
        val stringRequest = StringRequest(Request.Method.GET, url,
            { response ->
                when {
                    response.contains("UNLOCKED") -> setStatusTerbuka()
                    response.contains("READY_TO_SCAN") -> {
                        txtStatus?.text = getString(R.string.status_ready_to_scan)
                        cardSecurityStatus?.setCardBackgroundColor("#F57C00".toColorInt())
                        setMakeCircle(ledOn, "#F57C00".toColorInt())
                        setMakeCircle(ledOff, Color.DKGRAY)
                    }
                    else -> setStatusTerkunci()
                }
            },
            { _ -> setStatusTerkunci() }
        )
        stringRequest.retryPolicy = DefaultRetryPolicy(
            3000,
            0,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        queue?.add(stringRequest)
    }

    private fun setStatusTerbuka() {
        txtStatus?.text = getString(R.string.status_terbuka)
        cardSecurityStatus?.setCardBackgroundColor("#388E3C".toColorInt())
        setMakeCircle(ledOn, "#4DFF4D".toColorInt())
        setMakeCircle(ledOff, Color.DKGRAY)
    }

    private fun setStatusTerkunci() {
        txtStatus?.text = getString(R.string.status_terkunci)
        cardSecurityStatus?.setCardBackgroundColor("#D32F2F".toColorInt())
        setMakeCircle(ledOn, Color.DKGRAY)
        setMakeCircle(ledOff, "#FF4D4D".toColorInt())
    }

    private fun checkAndEnrollFingerprint() {
        var slotKosong = -1
        for (i in 1..10) {
            if (!sharedPreferences.contains("finger_$i")) {
                slotKosong = i
                break
            }
        }
        if (slotKosong == -1) {
            Toast.makeText(this, "Penyimpanan Penuh! Maksimal 10 Sidik Jari.", Toast.LENGTH_LONG).show()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Daftar Jari Baru (Slot $slotKosong)")
        val input = EditText(this)
        input.hint = "Masukkan Nama Pemilik Jari"
        builder.setView(input)

        builder.setPositiveButton("Mulai Scan") { _, _ ->
            val namaJari = input.text.toString().trim()
            if (namaJari.isNotEmpty()) {
                sharedPreferences.edit { putString("finger_$slotKosong", namaJari) }
                sendCommand("$ipEsp32/enroll?id=$slotKosong")
                Toast.makeText(this, "Silakan tempelkan jari ke sensor!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Nama tidak boleh kosong!", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Batal") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun showDeleteFingerprintDialog() {
        val registeredFingers = ArrayList<String>()
        val slotIds = ArrayList<Int>()
        for (i in 1..10) {
            val name = sharedPreferences.getString("finger_$i", null)
            if (name != null) {
                registeredFingers.add("ID $i: $name")
                slotIds.add(i)
            }
        }
        if (registeredFingers.isEmpty()) {
            Toast.makeText(this, "Belum ada sidik jari yang terdaftar.", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(8, 8, 8, 8)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pilih Jari yang Ingin Dihapus")
            .setNegativeButton("Kembali", null)

        val alertDialog = dialog.create()

        for (i in registeredFingers.indices) {
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            rowLayout.gravity = Gravity.CENTER_VERTICAL
            val rowPadding = (12 * resources.displayMetrics.density).toInt()
            rowLayout.setPadding(rowPadding, rowPadding, rowPadding, rowPadding)

            val txtNama = TextView(this)
            txtNama.text = registeredFingers[i]
            txtNama.textSize = 16f
            txtNama.setTextColor(Color.BLACK)
            val params = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            txtNama.layoutParams = params

            val btnDelete = Button(this)
            btnDelete.text = "✕"
            btnDelete.textSize = 16f
            btnDelete.setTextColor(Color.WHITE)
            btnDelete.setBackgroundColor("#BA1A1A".toColorInt())
            val btnSize = (40 * resources.displayMetrics.density).toInt()
            val btnParams = LinearLayout.LayoutParams(btnSize, btnSize)
            btnParams.marginStart = (8 * resources.displayMetrics.density).toInt()
            btnDelete.layoutParams = btnParams
            btnDelete.setPadding(0, 0, 0, 0)

            val targetId = slotIds[i]
            val targetName = registeredFingers[i]

            val aksiHapus = {
                alertDialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Konfirmasi Hapus")
                    .setMessage("Apakah Anda yakin ingin menghapus $targetName?")
                    .setPositiveButton("Ya, Hapus") { _, _ ->
                        sharedPreferences.edit { remove("finger_$targetId") }
                        sendCommand("$ipEsp32/delete?id=$targetId")
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }

            txtNama.setOnClickListener { aksiHapus() }
            btnDelete.setOnClickListener { aksiHapus() }

            rowLayout.addView(txtNama)
            rowLayout.addView(btnDelete)
            container.addView(rowLayout)

            if (i < registeredFingers.indices.last) {
                val divider = View(this)
                divider.setBackgroundColor("#EEEEEE".toColorInt())
                divider.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                container.addView(divider)
            }
        }

        alertDialog.setView(container)
        alertDialog.show()
    }

    fun sendCommand(url: String) {
        val stringRequest = StringRequest(Request.Method.GET, url,
            { response ->
                Toast.makeText(this, "Respon: $response", Toast.LENGTH_SHORT).show()
                when {
                    response.contains("READY_TO_SCAN") -> {
                        txtStatus?.text = getString(R.string.status_ready_to_scan)
                        cardSecurityStatus?.setCardBackgroundColor("#F57C00".toColorInt())
                        setMakeCircle(ledOn, "#F57C00".toColorInt())
                        setMakeCircle(ledOff, Color.DKGRAY)
                    }
                    response.contains("UNLOCKED") -> setStatusTerbuka()
                    response.contains("LOCKED")   -> setStatusTerkunci()
                    response.contains("MUTED") -> {
                        Toast.makeText(this, "Buzzer berhasil dimatikan!", Toast.LENGTH_SHORT).show()
                    }
                    response == "ON" || response.trim() == "ON" -> {
                        Toast.makeText(this, "Buzzer aktif kembali!", Toast.LENGTH_SHORT).show()
                    }
                    response.contains("OK_RESTARTING") -> {
                        Toast.makeText(this, "WiFi Berubah. Silakan konek ulang!", Toast.LENGTH_LONG).show()
                    }
                }
            },
            { _ ->
                Toast.makeText(this, "Gagal! Pastikan HP terhubung ke WiFi Motor", Toast.LENGTH_SHORT).show()
            }
        )
        stringRequest.retryPolicy = DefaultRetryPolicy(
            5000,
            0,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        queue?.add(stringRequest)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
    }
}