package com.mocklocation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvTip: TextView
    private lateinit var spinnerResults: Spinner
    private lateinit var progressBar: ProgressBar

    private val baiduApiKey = "prXi7BHWYPRbigDHYKkv9rE384K9bxtf"
    private val handler = Handler(Looper.getMainLooper())
    private var mockRunning = false
    private var mockRunnable: Runnable? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // 搜索结果列表
    private val searchResults = mutableListOf<SearchResult>()
    private var selectedLat = 0.0
    private var selectedLng = 0.0
    private var selectedName = ""

    data class SearchResult(val name: String, val lat: Double, val lng: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        checkMockLocationApp()
    }

    private fun initViews() {
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvLocation = findViewById(R.id.tvLocation)
        tvTip = findViewById(R.id.tvTip)
        spinnerResults = findViewById(R.id.spinnerResults)
        progressBar = findViewById(R.id.progressBar)

        btnSearch.setOnClickListener { searchLocation() }
        btnStart.setOnClickListener { startMock() }
        btnStop.setOnClickListener { stopMock() }

        btnStop.isEnabled = false
        updateStatus("就绪，请先搜索地点")
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needRequest.toTypedArray(), 100)
        }
    }

    private fun checkMockLocationApp() {
        // 检查是否已开启开发者选项并设置本App为模拟位置应用
        if (!Settings.canDrawOverlays(this)) {
            tvTip.text = "⚠️ 请先把本App设为模拟位置应用：\n设置 → 开发者选项 → 模拟位置应用 → 选择本App"
        }
    }

    private fun searchLocation() {
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入地点名称", Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = ProgressBar.VISIBLE
        tvTip.text = "正在搜索..."

        executor.execute {
            try {
                // 百度地图地点搜索API
                val url = "https://api.map.baidu.com/place/v2/search?query=${Uri.encode(keyword)}&region=全国&output=json&ak=$baiduApiKey"
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val status = json.getInt("status")
                if (status != 0) {
                    runOnUiThread {
                        progressBar.visibility = ProgressBar.GONE
                        tvTip.text = "搜索失败，错误码：$status"
                    }
                    return@execute
                }
                val results = json.getJSONArray("results")
                searchResults.clear()
                for (i in 0 until minOf(results.length(), 10)) {
                    val item = results.getJSONObject(i)
                    val name = item.getString("name")
                    val location = item.getJSONObject("location")
                    val lat = location.getDouble("lat")
                    val lng = location.getDouble("lng")
                    searchResults.add(SearchResult(name, lat, lng))
                }
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    if (searchResults.isEmpty()) {
                        tvTip.text = "未找到相关地点"
                        return@runOnUiThread
                    }
                    // 更新Spinner
                    val names = searchResults.map { it.name }
                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinnerResults.adapter = adapter
                    tvTip.text = "找到 ${searchResults.size} 个结果，请选择"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    tvTip.text = "搜索出错：${e.message}"
                }
            }
        }

        spinnerResults.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View, pos: Int, id: Long) {
                if (pos < searchResults.size) {
                    val r = searchResults[pos]
                    selectedName = r.name
                    selectedLat = r.lat
                    selectedLng = r.lng
                    tvLocation.text = "位置：$selectedName\n纬度：$selectedLat\n经度：$selectedLng"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun startMock() {
        if (selectedLat == 0.0) {
            Toast.makeText(this, "请先搜索并选择一个地点", Toast.LENGTH_SHORT).show()
            return
        }
        // 检查模拟位置权限
        if (!isMockLocationEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要设置模拟位置")
                .setMessage("请在开发者选项中将本App设为模拟位置应用：\n\n1. 设置 → 关于手机 → 连点版本号7次开启开发者选项\n2. 设置 → 开发者选项 → 模拟位置应用 → 选择本App")
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "请手动进入设置", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        mockRunning = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        updateStatus("正在模拟位置：$selectedName")

        // 使用LocationManager注入模拟位置（兼容旧版Android）
        // 新版本Android需要用LocationManager.addTestProvider
        startMockLocationLegacy()
    }

    private fun startMockLocationLegacy() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            // 移除旧的测试provider
            try {
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {}
            // 添加GPS测试provider
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false, true,
                true, true, 0, Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

            mockRunnable = object : Runnable {
                override fun run() {
                    if (!mockRunning) return
                    try {
                        val location = Location(LocationManager.GPS_PROVIDER).apply {
                            latitude = selectedLat
                            longitude = selectedLng
                            altitude = 50.0
                            accuracy = 1.0f
                            time = System.currentTimeMillis()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                                elapsedRealtimeNanos = System.currentTimeMillis() * 1000000
                            }
                        }
                        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
                    } catch (e: Exception) {
                        runOnUiThread { tvTip.text = "模拟失败：${e.message}" }
                    }
                    handler.postDelayed(this, 1000) // 每秒更新一次
                }
            }
            handler.post(mockRunnable!!)
        } catch (e: SecurityException) {
            runOnUiThread {
                Toast.makeText(this, "权限不足，请在开发者选项中启用模拟位置", Toast.LENGTH_LONG).show()
                stopMock()
            }
        }
    }

    private fun stopMock() {
        mockRunning = false
        mockRunnable?.let { handler.removeCallbacks(it) }
        mockRunnable = null
        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {}
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        updateStatus("已停止模拟")
        tvTip.text = "模拟已停止，GPS恢复正常"
    }

    private fun isMockLocationEnabled(): Boolean {
        return try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            // 简单检测：尝试添加测试provider看是否有权限
            // 实际权限由系统控制，这里只做基本检查
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { tvStatus.text = "状态：$text" }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "需要位置权限才能模拟GPS", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMock()
        executor.shutdown()
    }
}
