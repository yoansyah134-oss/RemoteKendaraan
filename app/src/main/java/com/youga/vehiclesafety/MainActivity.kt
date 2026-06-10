package com.youga.vehiclesafety

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
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

    private val IP_ESP32: String = "http://192.168.4.1"
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inisialisasi Elemen UI
        txtStatus = findViewById(R.id.txtStatus)
        cardSecurityStatus = findViewById(R.id.cardSecurityStatus)
        ledOn = findViewById(R.id.ledOn)
        ledOff = findViewById(R.id.ledOff)
        etSsid = findViewById(R.id.etSsid)
        etPassword = findViewById(R.id.etPassword)
        queue = Volley.newRequestQueue(this)

        sharedPreferences = getSharedPreferences("FingerprintPrefs", Context.MODE_PRIVATE)

        // Membuat bentuk indikator LED menjadi bulat sempurna
        setMakeCircle(ledOn, Color.DKGRAY)
        setMakeCircle(ledOff, Color.parseColor("#BA1A1A"))

        // Monitor koneksi WiFi ke ESP32
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startWifiMonitor()

        // 1. PANEL KONTROL KENDARAAN (ON/OFF)
        findViewById<Button>(R.id.btnOnScan).setOnClickListener { sendCommand("$IP_ESP32/scan_ready") }
        findViewById<Button>(R.id.btnOffTotal).setOnClickListener { sendCommand("$IP_ESP32/lock") }

        // 2. PANEL KONTROL BUZZER
        findViewById<Button>(R.id.btnBuzzerOn).setOnClickListener { sendCommand("$IP_ESP32/buzzer?mode=on") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { sendCommand("$IP_ESP32/buzzer?mode=mute") }

        // 3. FINGERPRINT MANAGER
        findViewById<Button>(R.id.btnDaftarJari).setOnClickListener { checkAndEnrollFingerprint() }
        findViewById<Button>(R.id.btnKelolaJari).setOnClickListener { showDeleteFingerprintDialog() }

        // 4. PENGATURAN WIFI
        findViewById<Button>(R.id.btnRestartWifi).setOnClickListener {
            val ssid = etSsid?.text.toString()
            val pass = etPassword?.text.toString()

            if (ssid.isNotEmpty() && pass.length >= 8) {
                sendCommand("$IP_ESP32/wifi?ssid=$ssid&pass=$pass")
            } else {
                Toast.makeText(this, "Password minimal 8 karakter!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Fungsi Pembantu untuk Membuat Komponen View Menjadi Bulat (Lampu LED)
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
                runOnUiThread { checkEsp32Connection() }
            }
            override fun onLost(network: Network) {
                runOnUiThread { setStatusTerkunci() }
            }
        }
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun checkEsp32Connection() {
        val url = "$IP_ESP32/status"
        val stringRequest = StringRequest(Request.Method.GET, url,
            { _ -> setStatusTerbuka() },
            { _ -> setStatusTerkunci() }
        )
        queue?.add(stringRequest)
    }

    private fun setStatusTerbuka() {
        txtStatus?.text = "Terbuka"
        cardSecurityStatus?.setCardBackgroundColor(Color.parseColor("#388E3C"))
        setMakeCircle(ledOn, Color.parseColor("#4DFF4D"))
        setMakeCircle(ledOff, Color.DKGRAY)
    }

    private fun setStatusTerkunci() {
        txtStatus?.text = "Terkunci"
        cardSecurityStatus?.setCardBackgroundColor(Color.parseColor("#D32F2F"))
        setMakeCircle(ledOn, Color.DKGRAY)
        setMakeCircle(ledOff, Color.parseColor("#FF4D4D"))
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
                sharedPreferences.edit().putString("finger_$slotKosong", namaJari).apply()
                sendCommand("$IP_ESP32/enroll?id=$slotKosong")
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

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pilih Jari yang Ingin Dihapus")
        builder.setItems(registeredFingers.toTypedArray()) { _, index ->
            val targetId = slotIds[index]
            val targetName = registeredFingers[index]

            AlertDialog.Builder(this)
                .setTitle("Konfirmasi Hapus")
                .setMessage("Apakah Anda yakin ingin menghapus $targetName?")
                .setPositiveButton("Ya, Hapus") { _, _ ->
                    sharedPreferences.edit().remove("finger_$targetId").apply()
                    sendCommand("$IP_ESP32/delete?id=$targetId")
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        builder.setNegativeButton("Kembali", null)
        builder.show()
    }

    fun sendCommand(url: String) {
        val stringRequest = StringRequest(Request.Method.GET, url,
            { response ->
                Toast.makeText(this, "Respon: $response", Toast.LENGTH_SHORT).show()

                when {
                    response.contains("READY_TO_SCAN") -> {
                        txtStatus?.text = "Standby (Ready to Scan)"
                        cardSecurityStatus?.setCardBackgroundColor(Color.parseColor("#F57C00"))
                        setMakeCircle(ledOn, Color.parseColor("#F57C00"))
                        setMakeCircle(ledOff, Color.DKGRAY)
                    }
                    response.contains("UNLOCKED") -> {
                        setStatusTerbuka()
                    }
                    response.contains("LOCKED") -> {
                        setStatusTerkunci()
                    }
                    response.contains("MUTED") -> {
                        Toast.makeText(this, "Buzzer berhasil dimatikan!", Toast.LENGTH_SHORT).show()
                    }
                    response.contains("ON") -> {
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
        queue?.add(stringRequest)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
    }
}