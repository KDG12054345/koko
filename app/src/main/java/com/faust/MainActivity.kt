package com.faust

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.faust.database.FaustDatabase
import com.faust.models.BlockedApp
import com.faust.models.UserTier
import com.faust.services.AppBlockingService
import com.faust.services.PointMiningService
import com.faust.services.WeeklyResetService
import com.faust.ui.AppSelectionDialog
import com.faust.ui.BlockedAppAdapter
import com.faust.utils.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(this)
    }

    private lateinit var recyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var adapter: BlockedAppAdapter
    private lateinit var textCurrentPoints: TextView
    private lateinit var buttonAddApp: Button
    private lateinit var fabStartService: FloatingActionButton

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            startServices()
        } else {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (checkUsageStatsPermission()) {
            startServices()
        } else {
            Toast.makeText(this, "사용 통계 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()
        setupViews()
        setupRecyclerView()
        checkPermissions()
        observePoints() // Flow로 포인트 변경 감지
        loadBlockedApps()

        // 주간 정산 스케줄링
        WeeklyResetService.scheduleWeeklyReset(this)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupViews() {
        textCurrentPoints = findViewById(R.id.textCurrentPoints)
        buttonAddApp = findViewById(R.id.buttonAddApp)
        fabStartService = findViewById(R.id.fabStartService)

        buttonAddApp.setOnClickListener {
            showAddAppDialog()
        }

        fabStartService.setOnClickListener {
            if (checkAllPermissions()) {
                startServices()
            } else {
                checkPermissions()
            }
        }

    }

    /**
     * DB의 포인트 변경을 Flow로 관찰하여 UI를 업데이트합니다.
     * 변경사항이 있을 때만 UI가 업데이트되므로 효율적입니다.
     */
    private fun observePoints() {
        lifecycleScope.launch {
            database.pointTransactionDao().getTotalPointsFlow()
                .onEach { points ->
                    // 포인트가 변경될 때만 UI 업데이트
                    updatePointsDisplay(points)
                }
                .collect()
        }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerViewBlockedApps)
        adapter = BlockedAppAdapter { blockedApp ->
            removeBlockedApp(blockedApp)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun showAddAppDialog() {
        // Free 티어는 최대 1개만 허용
        val userTier = preferenceManager.getUserTier()
        val maxApps = when (userTier) {
            UserTier.FREE -> 1
            UserTier.STANDARD -> 3
            UserTier.FAUST_PRO -> Int.MAX_VALUE
        }

        lifecycleScope.launch {
            val currentCount = database.appBlockDao().getBlockedAppCount()
            if (currentCount >= maxApps) {
                Toast.makeText(
                    this@MainActivity,
                    "최대 $maxApps 개의 앱만 차단할 수 있습니다",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val dialog = AppSelectionDialog { app ->
                addBlockedApp(app)
            }
            dialog.show(supportFragmentManager, "AppSelectionDialog")
        }
    }

    private fun addBlockedApp(app: BlockedApp) {
        lifecycleScope.launch {
            try {
                database.appBlockDao().insertBlockedApp(app)
                loadBlockedApps()
                Toast.makeText(this@MainActivity, "${app.appName} 추가됨", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "추가 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeBlockedApp(app: BlockedApp) {
        AlertDialog.Builder(this)
            .setTitle("앱 제거")
            .setMessage("${app.appName}을(를) 차단 목록에서 제거하시겠습니까?")
            .setPositiveButton("제거") { _, _ ->
                lifecycleScope.launch {
                    database.appBlockDao().deleteBlockedApp(app)
                    loadBlockedApps()
                    Toast.makeText(this@MainActivity, "${app.appName} 제거됨", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadBlockedApps() {
        lifecycleScope.launch {
            database.appBlockDao().getAllBlockedApps().collect { apps ->
                adapter.submitList(apps)
            }
        }
    }

    /**
     * 포인트 표시를 업데이트합니다.
     * @param points 표시할 포인트 값
     */
    private fun updatePointsDisplay(points: Int) {
        textCurrentPoints.text = getString(R.string.current_points, points)
    }

    private fun checkPermissions() {
        if (!checkAllPermissions()) {
            showPermissionDialog()
        }
    }

    private fun checkAllPermissions(): Boolean {
        return checkUsageStatsPermission() && checkOverlayPermission()
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOpsManager = getSystemService(android.app.AppOpsManager::class.java)
        val mode = appOpsManager?.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("앱 차단 기능을 사용하려면 다음 권한이 필요합니다:\n\n" +
                    "1. 사용 통계 권한\n" +
                    "2. 다른 앱 위에 표시 권한")
            .setPositiveButton("설정") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun requestPermissions() {
        if (!checkUsageStatsPermission()) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            usageStatsPermissionLauncher.launch(intent)
        } else if (!checkOverlayPermission()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startServices() {
        if (checkAllPermissions()) {
            AppBlockingService.startService(this)
            PointMiningService.startService(this)
            Toast.makeText(this, "서비스 시작됨", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "모든 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }
}
