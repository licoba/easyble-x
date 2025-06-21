package com.wandersnail.bledemo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ToastUtils
import com.kongzue.dialogx.dialogs.PopNotification
import com.permissionx.guolindev.PermissionX
import com.wandersnail.bledemo.databinding.ActivityAncsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class AncsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAncsBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null
    private var bleServer: BleServer? = null
    private var serverStatusJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var isServiceDiscovered = false
    private val ancsUtil = ANCSUtil()
    private var notificationSourceEnabledTime: Long = 0 // 通知源启用时间戳

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logAndUpdateUI("连接状态变化: status=$status, newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "设备已连接，开始发现服务")
                    logAndUpdateUI("已连接到设备，正在发现服务...")
                    isServiceDiscovered = false
                    gatt.requestMtu(512)
                    logAndUpdateUI("已请求MTU: 512")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logAndUpdateUI("设备已断开连接")
                    gatt.close()
                }
            } else {
                logAndUpdateUI(Log.ERROR, "连接失败: $status")
                gatt.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logAndUpdateUI("服务发现: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val ancsService = ancsUtil.getAncsService(gatt)
                if (ancsService != null) {
                    logAndUpdateUI("发现ANCS服务")
                    isServiceDiscovered = true

                    notificationSourceChar = ancsUtil.getNotificationSourceCharacteristic(gatt)
                    if (notificationSourceChar != null) {
                        logAndUpdateUI("找到Notification Source特征")
                        binding.btnEnableNotifySource.isEnabled = true
                    } else {
                        logAndUpdateUI(Log.ERROR, "未找到Notification Source特征")
                        binding.btnEnableNotifySource.isEnabled = false
                    }

                    dataSourceChar = ancsUtil.getDataSourceCharacteristic(gatt)
                    if (dataSourceChar != null) {
                        logAndUpdateUI("找到Data Source特征")
                        binding.btnEnableDataSource.isEnabled = true
                    } else {
                        logAndUpdateUI(Log.ERROR, "未找到Data Source特征")
                        binding.btnEnableDataSource.isEnabled = false
                    }
                } else {
                    logAndUpdateUI(Log.ERROR, "未找到ANCS服务")
                    val deviceAddress = gatt.device.address
                    logAndUpdateUI("设备 $deviceAddress 不支持ANCS服务，已记录")
                    gatt.close()
                }
            } else {
                logAndUpdateUI(Log.ERROR, "服务发现失败: $status")
                val deviceAddress = gatt.device.address
                logAndUpdateUI("设备 $deviceAddress 服务发现失败，已记录")
                gatt.close()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logAndUpdateUI("描述符写入: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "描述符写入成功")
            } else {
                Log.e(TAG, "描述符写入失败: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logAndUpdateUI("特征值写入成功 UUID=${characteristic.uuid}")
            } else {
                logAndUpdateUI(Log.ERROR, "特征值写入失败: $status UUID=${characteristic.uuid}")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ANCSUtil.NOTIFICATION_SOURCE_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    // 检查是否在忽略期内
                    val currentTime = System.currentTimeMillis()
                    val timeSinceEnabled = currentTime - notificationSourceEnabledTime
                    if (timeSinceEnabled < IGNORE_DURATION_MS) {
                        logAndUpdateUI("忽略监听成功后 $IGNORE_DURATION_MS ms内的通知")
                        return
                    }
                    logAndUpdateUI("收到ANCS通知数据，长度: ${data.size}")
                    parseAncsNotification(data)
                }
            } else if (characteristic.uuid == ANCSUtil.DATA_SOURCE_UUID) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    logAndUpdateUI("收到通知详细内容，长度: ${data.size}")
                    parseNotificationDetails(data)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logAndUpdateUI("MTU协商成功: $mtu")
            } else {
                logAndUpdateUI(Log.ERROR, "MTU协商失败: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            val deviceName = device.name
            val deviceAddress = device.address
            Log.d(TAG,"发现设备: $deviceName ($deviceAddress), RSSI: $rssi")
            if (deviceName != null && deviceName.lowercase(Locale.getDefault()).contains("ancs")) {
                logAndUpdateUI("找到名称包含ANCS的设备: $deviceName ($deviceAddress)")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败，错误码: $errorCode")
            logAndUpdateUI("扫描失败: $errorCode")
            isScanning = false
            binding.btnScan.text = "扫描"
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAncsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.title = "ANCS"
        }

        binding.btnScan.setOnClickListener {
            if (isScanning) {
                stopScan()
                binding.btnScan.text = "扫描"
            } else {
                startScan()
                binding.btnScan.text = "停止"
            }
        }

        binding.btnEnableNotifySource.setOnClickListener {
            if (bluetoothGatt != null && notificationSourceChar != null) {
                enableNotificationSource()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnEnableDataSource.setOnClickListener {
            if (bluetoothGatt != null && dataSourceChar != null) {
                enableDataSource()
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnConnect.setOnClickListener {
            val macAddress = binding.etMacAddress.text.toString().trim()
            if (macAddress.isEmpty()) {
                Toast.makeText(this, "请输入MAC地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            stopScan()
            val device = bluetoothAdapter!!.getRemoteDevice(macAddress)
            if (device != null) {
                connectToDevice(device)
            } else {
                Toast.makeText(this, "无效的MAC地址", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleServer.setOnClickListener {
            if (bleServer == null) {
                bleServer = BleServer(this)
                val success = bleServer!!.startServer()
                if (success) {
                    binding.btnToggleServer.text = "停止BLE服务器"
                    binding.tvServerStatus.text = "服务器状态: 运行中"
                    logAndUpdateUI("BLE服务器启动成功")
                    Toast.makeText(this, "BLE服务器启动成功", Toast.LENGTH_SHORT).show()
                    startServerStatusUpdater()
                } else {
                    logAndUpdateUI("BLE服务器启动失败")
                    Toast.makeText(this, "BLE服务器启动失败", Toast.LENGTH_SHORT).show()
                    bleServer = null
                }
            } else {
                bleServer!!.stopServer()
                binding.btnToggleServer.text = "启动BLE服务器"
                binding.tvServerStatus.text = "服务器状态: 已停止"
                logAndUpdateUI("BLE服务器已停止")
                Toast.makeText(this, "BLE服务器已停止", Toast.LENGTH_SHORT).show()
                stopServerStatusUpdater()
                bleServer = null
            }
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        // 使用 PermissionX 检查和请求权限
        checkAndRequestPermissions()
    }

    /**
     * 使用 PermissionX 检查和请求蓝牙权限
     */
    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        PermissionX.init(this)
            .permissions(*permissions)
            .onExplainRequestReason { scope, deniedList ->
                val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "ANCS功能需要蓝牙扫描、连接和广播权限才能正常工作"
                } else {
                    "ANCS功能需要位置权限才能扫描蓝牙设备"
                }
                scope.showRequestReasonDialog(deniedList, message, "确定", "取消")
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(
                    deniedList,
                    "您需要在设置中手动允许必要的权限才能使用ANCS功能",
                    "去设置",
                    "取消"
                )
            }
            .request { allGranted, _, deniedList ->
                if (allGranted) {
                    logAndUpdateUI("所有权限已授予")
                    checkBluetoothEnabled()
                } else {
                    logAndUpdateUI("以下权限被拒绝: $deniedList")
                    Toast.makeText(this, "权限被拒绝，无法使用ANCS功能", Toast.LENGTH_SHORT).show()
                }
            }
    }

    @get:SuppressLint("MissingPermission")
    private val connectedAncsDevice: BluetoothDevice?
        get() {
            for (device in bluetoothAdapter!!.bondedDevices) {
                if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                    Log.i(TAG, "找到LE设备: ${device.address}")
                    return device
                }
            }
            logAndUpdateUI("未找到已连接的ANCS设备，请点击扫描按钮开始扫描")
            return null
        }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!isScanning) {
            logAndUpdateUI("正在扫描BLE设备...")
            scanTimeoutJob = lifecycleScope.launch {
                delay(SCAN_PERIOD)
                stopScan()
            }

            isScanning = true
            if (bluetoothLeScanner == null) {
                bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
            }

            if (bluetoothLeScanner != null) {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build()

                bluetoothLeScanner!!.startScan(null, settings, scanCallback)
            } else {
                Log.e(TAG, "无法获取BluetoothLeScanner")
                logAndUpdateUI("无法启动扫描")
                isScanning = false
                binding.btnScan.text = "扫描"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null

        if (isScanning && bluetoothLeScanner != null) {
            isScanning = false
            bluetoothLeScanner!!.stopScan(scanCallback)
            logAndUpdateUI("扫描结束")
            binding.btnScan.text = "扫描"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "开始连接设备: ${device.address}")
        logAndUpdateUI("正在连接到设备...")

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            device.createBond()
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun enableDataSource() {
        if (dataSourceChar != null) {
            val success = bluetoothGatt!!.setCharacteristicNotification(dataSourceChar, true)
            if (!success) {
                logAndUpdateUI(Log.ERROR, "启用Data Source通知失败")
                return
            }

            val descriptor = dataSourceChar!!.getDescriptor(ANCSUtil.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val writeSuccess = bluetoothGatt!!.writeDescriptor(descriptor)
                if (!writeSuccess) {
                    logAndUpdateUI(Log.ERROR, "写入Data Source描述符失败")
                    return
                }
                logAndUpdateUI("Data Source配置完成")
                binding.btnEnableDataSource.isEnabled = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotificationSource() {
        if (notificationSourceChar != null) {
            val success =
                bluetoothGatt!!.setCharacteristicNotification(notificationSourceChar, true)
            if (!success) {
                logAndUpdateUI(Log.ERROR, "启用Notification Source通知失败")
                return
            }

            val descriptor =
                notificationSourceChar!!.getDescriptor(ANCSUtil.CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val writeSuccess = bluetoothGatt!!.writeDescriptor(descriptor)
                if (!writeSuccess) {
                    logAndUpdateUI(Log.ERROR, "写入Notification Source描述符失败")
                    return
                }
                logAndUpdateUI("Notification Source配置完成")
                binding.btnEnableNotifySource.isEnabled = false
                notificationSourceEnabledTime = System.currentTimeMillis()
                logAndUpdateUI("已记录通知源启用时间，将忽略后续3秒内的通知")
            }
        }
    }

    private fun parseAncsNotification(data: ByteArray) {
        val notificationInfo = ancsUtil.parseAncsNotification(data)
        if (notificationInfo != null) {
            logAndUpdateUI("收到通知: eventId=${notificationInfo.eventId}, flags=${notificationInfo.eventFlags}, categoryId=${notificationInfo.categoryId}, uid=${notificationInfo.notificationUid}")
            if (ancsUtil.isNewSocialNotification(
                    notificationInfo.eventId,
                    notificationInfo.categoryId
                )
            ) {
                requestNotificationDetails(notificationInfo.notificationUid)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNotificationDetails(notificationUid: Int) {
        val controlPoint = ancsUtil.getControlPointCharacteristic(bluetoothGatt!!)
        if (controlPoint != null) {
            val command = ancsUtil.buildGetNotificationAttributesCommand(notificationUid)
            controlPoint.setValue(command)
            val success = bluetoothGatt!!.writeCharacteristic(controlPoint)
            val commandHex = command.joinToString(" ") { String.format("%02X", it) }
            Log.d(TAG, "发送获取通知详细内容的请求: $success, command: $commandHex")
            if (!success) {
                logAndUpdateUI(Log.ERROR, "写入特征值失败")
            }
        }
    }

    private fun parseNotificationDetails(data: ByteArray?) {
        val details = ancsUtil.parseNotificationDetails(data)
        if (details != null) {
            val allDetails = StringBuilder()
            allDetails.append("Notification (UID: ${details.notificationUid}):\n")
            details.attributes.forEach { (name, value) ->
                val detail = "  - $name: $value"
                allDetails.append(detail).append("\n")
            }
            logAndUpdateUI(allDetails.toString())
            val msg =
                "ANCS消息\n应用: ${details.appIdentifier}\n标题: ${details.title}\n内容: ${details.message}"
            PopNotification.show(msg)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        if (bluetoothGatt != null) {
            bluetoothGatt!!.close()
        }
        if (bleServer != null) {
            bleServer!!.stopServer()
            bleServer = null
        }
        stopServerStatusUpdater()
    }

    @SuppressLint("DefaultLocale")
    private fun startServerStatusUpdater() {
        stopServerStatusUpdater()
        serverStatusJob = lifecycleScope.launch {
            while (true) {
                val server = bleServer
                if (server != null && server.isServerRunning) {
                    val heartbeatCount = server.heartbeatCounter
                    val deviceCount = server.connectedDeviceCount
                    val isAdvertising = server.isAdvertising
                    val status = String.format(
                        "服务器状态: 运行中 (心跳: %d, 设备: %d, 广播: %s)",
                        heartbeatCount, deviceCount, if (isAdvertising) "开启" else "关闭"
                    )
                    binding.tvServerStatus.text = status
                }
                delay(1000)
            }
        }
        logAndUpdateUI("启动服务器状态更新器")
    }

    private fun stopServerStatusUpdater() {
        val job = serverStatusJob
        if (job != null) {
            job.cancel()
            serverStatusJob = null
            logAndUpdateUI("停止服务器状态更新器")
        }
    }

    private fun checkBluetoothEnabled() {
        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            connectedAncsDevice
        }
    }


    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectedAncsDevice
        } else {
            ToastUtils.showShort( "请开启蓝牙以使用ANCS功能")
        }
    }

    private fun logAndUpdateUI(level: Int, message: String) {
        when (level) {
            Log.DEBUG -> Log.d(TAG, message)
            Log.INFO -> Log.i(TAG, message)
            Log.WARN -> Log.w(TAG, message)
            Log.ERROR -> Log.e(TAG, message)
        }
        runOnUiThread {
            binding.tvLog.appendLog(message)
        }
    }

    private fun logAndUpdateUI(message: String) {
        logAndUpdateUI(Log.INFO, message)
    }


    companion object {
        private const val TAG = "AncsActivity"
        private const val SCAN_PERIOD: Long = 10000
        private const val IGNORE_DURATION_MS = 3000L // 忽略持续时间3秒

    }
}