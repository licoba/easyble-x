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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kongzue.dialogx.dialogs.PopNotification
import com.wandersnail.bledemo.databinding.ActivityAncsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

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
    // 使用协程替代定时器
    private var connectionTimeoutJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private val triedDevices: MutableSet<String> = HashSet() // 记录已尝试过的设备
    private var isServiceDiscovered = false // 标记是否已发现服务

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(
                TAG,
                "onConnectionStateChange: status=$status, newState=$newState"
            )
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "设备已连接，开始发现服务")
                    runOnUiThread {
                        binding.tvStatus.appendLog("已连接到设备，正在发现服务...")
                    }
                    // 重置服务发现标记
                    isServiceDiscovered = false
                    // 启动连接超时定时器
                    startConnectionTimeout()
                    gatt.requestMtu(512)
                    Log.d(TAG, "已请求MTU: 512")
                    // 连接成功后开始发现服务
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "设备已断开连接")
                    runOnUiThread {
                        binding.tvStatus.appendLog("设备已断开连接")
                    }


                    // 停止超时定时器
                    stopConnectionTimeout()

                    gatt.close()
                }
            } else {
                Log.e(TAG, "连接失败，状态码: $status")
                runOnUiThread {
                    binding.tvStatus.appendLog("连接失败: $status")
                }


                // 停止超时定时器
                stopConnectionTimeout()

                gatt.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val ancsService = gatt.getService(ANCS_SERVICE_UUID)
                if (ancsService != null) {
                    Log.i(TAG, "成功发现ANCS服务")
                    runOnUiThread {
                        binding.tvStatus.appendLog("发现ANCS服务")
                    }


                    // 标记服务已发现，停止超时定时器
                    isServiceDiscovered = true
                    stopConnectionTimeout()

                    // 获取通知源特征
                    notificationSourceChar = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID)
                    if (notificationSourceChar != null) {
                        Log.d(TAG, "找到通知源特征")
                        runOnUiThread {
                            binding.tvStatus.appendLog("找到通知源特征")
                            binding.btnEnableNotifySource.isEnabled = true
                        }
                    } else {
                        Log.e(TAG, "未找到通知源特征")
                        runOnUiThread {
                            binding.tvStatus.appendLog("未找到通知源特征")
                            binding.btnEnableNotifySource.isEnabled = false
                        }
                    }

                    // 获取数据源特征
                    dataSourceChar = ancsService.getCharacteristic(DATA_SOURCE_UUID)
                    if (dataSourceChar != null) {
                        Log.d(TAG, "找到数据源特征")
                        runOnUiThread {
                            binding.tvStatus.appendLog("找到数据源特征")
                            binding.btnEnableDataSource.isEnabled = true
                        }
                    } else {
                        Log.e(TAG, "未找到数据源特征")
                        runOnUiThread {
                            binding.tvStatus.appendLog("未找到数据源特征")
                            binding.btnEnableDataSource.isEnabled = false
                        }
                    }
                } else {
                    Log.e(TAG, "未找到ANCS服务")
                    runOnUiThread {
                        binding.tvStatus.appendLog("未找到ANCS服务")
                    }


                    // 记录设备地址并断开连接
                    val deviceAddress = gatt.device.address
                    triedDevices.add(deviceAddress)
                    Log.d(
                        TAG,
                        "设备 $deviceAddress 不支持ANCS服务，已记录"
                    )

                    gatt.close()
                }
            } else {
                Log.e(TAG, "服务发现失败: $status")
                runOnUiThread {
                    binding.tvStatus.appendLog("服务发现失败: $status")
                }


                // 记录设备地址并断开连接
                val deviceAddress = gatt.device.address
                triedDevices.add(deviceAddress)
                Log.d(
                    TAG,
                    "设备 $deviceAddress 服务发现失败，已记录"
                )

                gatt.close()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite: status=$status")
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
            Log.d(TAG, "onCharacteristicWrite: status=" + status + ", UUID=" + characteristic.uuid)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "特征值写入成功")
                runOnUiThread {
                    binding.tvStatus.appendLog("特征值写入成功")
                }
            } else {
                Log.e(TAG, "特征值写入失败: $status")
                runOnUiThread {
                    binding.tvStatus.appendLog("特征值写入失败: $status")
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // 处理接收到的ANCS通知数据
            if (characteristic.uuid == NOTIFICATION_SOURCE_UUID) {
                val data = characteristic.value
                if (data != null && data.size > 0) {
                    Log.d(TAG, "收到ANCS通知数据，长度: " + data.size)
                    parseAncsNotification(data)
                }
            } else if (characteristic.uuid == DATA_SOURCE_UUID) {
                // 处理通知详细内容
                val data = characteristic.value
                if (data != null && data.size > 0) {
                    Log.d(TAG, "收到通知详细内容，长度: " + data.size)
                    parseNotificationDetails(data)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU协商成功: $mtu")
                runOnUiThread {
                    binding.tvStatus.appendLog("MTU协商成功: $mtu")
                }
            } else {
                Log.e(TAG, "MTU协商失败: $status")
                runOnUiThread {
                    binding.tvStatus.appendLog("MTU协商失败: $status")
                }
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


//            if(result.getRssi() < -40) return;
            Log.d(
                TAG,
                "发现设备: $deviceName ($deviceAddress), RSSI: $rssi"
            )


            // 检查是否已经尝试过这个设备
            if (triedDevices.contains(deviceAddress)) {
                Log.d(TAG, "设备 $deviceAddress 已尝试过，跳过")
                return
            }


            // 检查设备名称是否包含"ancs"（不区分大小写）
            if (deviceName != null && deviceName.lowercase(Locale.getDefault()).contains("ancs")) {
                Log.i(
                    TAG,
                    "找到名称包含ANCS的设备: $deviceName ($deviceAddress)"
                )
                // 记录设备地址，防止重复尝试
                triedDevices.add(deviceAddress)
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败，错误码: $errorCode")
            runOnUiThread {
                binding.tvStatus.appendLog("扫描失败: $errorCode")
            }
            isScanning = false
            binding.btnScan.text = "扫描"
        }
    }

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
            val macAddress = binding.etMacAddress.text.toString().trim { it <= ' ' }
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
                // 创建并启动BLE服务器
                bleServer = BleServer(this)
                val success = bleServer!!.startServer()
                if (success) {
                    binding.btnToggleServer.text = "停止BLE服务器"
                    binding.tvServerStatus.text = "服务器状态: 运行中"
                    binding.tvStatus.appendLog("BLE服务器启动成功")
                    Toast.makeText(this, "BLE服务器启动成功", Toast.LENGTH_SHORT).show()


                    // 启动状态更新器
                    startServerStatusUpdater()
                } else {
                    binding.tvStatus.appendLog("BLE服务器启动失败")
                    Toast.makeText(this, "BLE服务器启动失败", Toast.LENGTH_SHORT).show()
                    bleServer = null
                }
            } else {
                // 停止BLE服务器
                bleServer!!.stopServer()
                binding.btnToggleServer.text = "启动BLE服务器"
                binding.tvServerStatus.text = "服务器状态: 已停止"
                binding.tvStatus.appendLog("BLE服务器已停止")
                Toast.makeText(this, "BLE服务器已停止", Toast.LENGTH_SHORT).show()


                // 停止状态更新器
                stopServerStatusUpdater()
                bleServer = null
            }
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.adapter
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        // 检查并请求权限
        if (checkBluetoothPermissions()) {
            // 权限已授予，检查蓝牙是否开启
            checkBluetoothEnabled()
        } else {
            // 请求权限
            requestBluetoothPermissions()
        }
    }

    @get:SuppressLint("MissingPermission")
    private val connectedAncsDevice: BluetoothDevice?
        get() {
            // 获取所有已连接的设备
            Log.d(TAG, "开始查找已连接的ANCS设备")


            // 首先检查已配对的设备
            for (device in bluetoothAdapter!!.bondedDevices) {
                if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                    Log.i(
                        TAG,
                        "找到LE设备: " + device.address
                    )
                    return device
                }
            }


            // 如果没有找到已配对的设备，提示用户开始扫描
            Log.w(TAG, "未找到已连接的ANCS设备")
            runOnUiThread {
                binding.tvStatus.appendLog("未找到已连接的ANCS设备，请点击扫描按钮开始扫描")
            }
            return null
        }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!isScanning) {
            runOnUiThread {
                binding.tvStatus.appendLog("正在扫描BLE设备...")
            }

            // 清空已尝试设备的记录，重新开始尝试
            triedDevices.clear()
            Log.d(TAG, "清空已尝试设备记录，重新开始扫描")

            // 设置扫描超时
            scanTimeoutJob = lifecycleScope.launch {
                delay(SCAN_PERIOD)
                stopScan()
            }

            isScanning = true
            if (bluetoothLeScanner == null) {
                bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
            }

            if (bluetoothLeScanner != null) {
                // 优化扫描设置
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 低延迟模式(低功耗)
                    .setReportDelay(0) // 立即报告结果
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // 匹配所有广播
                    .build()


                // 不设置过滤器，扫描所有设备，在回调中根据名称过滤
                bluetoothLeScanner!!.startScan(null, settings, scanCallback)
            } else {
                Log.e(TAG, "无法获取BluetoothLeScanner")
                runOnUiThread {
                    binding.tvStatus.appendLog("无法启动扫描")
                }
                isScanning = false
                binding.btnScan.text = "扫描"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        // 取消扫描超时协程
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null

        if (isScanning && bluetoothLeScanner != null) {
            isScanning = false
            bluetoothLeScanner!!.stopScan(scanCallback)
            runOnUiThread {
                binding.tvStatus.appendLog("扫描结束")
                binding.btnScan.text = "扫描"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "开始连接设备: " + device.address)
        runOnUiThread {
            binding.tvStatus.appendLog("正在连接到设备...")
        }


        // 如果设备未配对，先配对
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            device.createBond()
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }


    @SuppressLint("MissingPermission")
    private fun enableDataSource() {
        if (dataSourceChar != null) {
            // 启用通知
            val success = bluetoothGatt!!.setCharacteristicNotification(dataSourceChar, true)
            if (!success) {
                Log.e(TAG, "启用数据源通知失败")
                runOnUiThread {
                    binding.tvStatus.appendLog("启用数据源通知失败")
                }
                return
            }

            val descriptor = dataSourceChar!!.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val writeSuccess = bluetoothGatt!!.writeDescriptor(descriptor)
                if (!writeSuccess) {
                    Log.e(TAG, "写入数据源描述符失败")
                    runOnUiThread {
                        binding.tvStatus.appendLog("写入数据源描述符失败")
                    }
                    return
                }
                Log.i(TAG, "数据源配置完成")
                runOnUiThread {
                    binding.tvStatus.appendLog("数据源配置完成")
                    binding.btnEnableDataSource.isEnabled = false
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun enableNotificationSource() {
        if (notificationSourceChar != null) {
            // 启用通知
            val success =
                bluetoothGatt!!.setCharacteristicNotification(notificationSourceChar, true)
            if (!success) {
                Log.e(TAG, "启用通知源通知失败")
                runOnUiThread {
                    binding.tvStatus.appendLog("启用通知源通知失败")
                }
                return
            }

            val descriptor = notificationSourceChar!!.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val writeSuccess = bluetoothGatt!!.writeDescriptor(descriptor)
                if (!writeSuccess) {
                    Log.e(TAG, "写入通知源描述符失败")
                    runOnUiThread {
                        binding.tvStatus.appendLog("写入通知源描述符失败")
                    }
                    return
                }
                Log.i(TAG, "通知源配置完成")
                runOnUiThread {
                    binding.tvStatus.appendLog("通知源配置完成")
                    binding.btnEnableNotifySource.isEnabled = false
                }
            }
        }
    }

    private fun parseAncsNotification(data: ByteArray) {
        // 解析ANCS通知数据
        // 数据格式：
        // - 字节0: 事件ID
        // - 字节1: 事件标志
        // - 字节2: 类别ID
        // - 字节3: 类别计数
        // - 字节4-7: 通知UID
        if (data.size < 8) {
            Log.e(TAG, "通知数据长度不足: " + data.size)
            return
        }

        val eventId = data[0].toInt() and 0xFF
        val eventFlags = data[1].toInt() and 0xFF
        val categoryId = data[2].toInt() and 0xFF
        val categoryCount = data[3].toInt() and 0xFF
        val notificationUid =
            (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) or ((data[6].toInt() and 0xFF) shl 16) or ((data[7].toInt() and 0xFF) shl 24)

        Log.d(
            TAG, String.format(
                "解析通知数据: eventId=%d, flags=%d, categoryId=%d, uid=%d",
                eventId, eventFlags, categoryId, notificationUid
            )
        )

        val eventType = getEventTypeString(eventId)
        val category = getCategoryString(categoryId)

        @SuppressLint("DefaultLocale") val notification = String.format(
            "通知ID: %d\n类型: %s\n分类: %s\n",
            notificationUid, eventType, category
        )

        runOnUiThread {
            binding.tvNotifications.appendLog(notification)
        }

        // 如果是新通知，请求详细内容
        if (eventId == 0 && categoryId == 4) { // 0 表示添加新通知
            requestNotificationDetails(notificationUid)
        }
    }

    private fun getEventTypeString(eventId: Int): String {
        return when (eventId) {
            0 -> "添加"
            1 -> "修改"
            2 -> "删除"
            else -> "未知"
        }
    }

    private fun getCategoryString(categoryId: Int): String {
        return when (categoryId) {
            0 -> "其他"
            1 -> "来电"
            2 -> "未接来电"
            3 -> "语音邮件"
            4 -> "社交"
            5 -> "日程"
            6 -> "邮件"
            7 -> "新闻"
            8 -> "健康与健身"
            9 -> "商业/金融"
            10 -> "位置"
            11 -> "娱乐"
            else -> "未知类别"
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
        // 停止连接超时定时器
        stopConnectionTimeout()
        if (bluetoothGatt != null) {
            bluetoothGatt!!.close()
        }
        // 停止BLE服务器
        if (bleServer != null) {
            bleServer!!.stopServer()
            bleServer = null
        }
        // 停止状态更新器
        stopServerStatusUpdater()
    }

    // 启动服务器状态更新器
    private fun startServerStatusUpdater() {
        stopServerStatusUpdater() // 先停止之前的更新器
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
                // 每秒更新一次
                delay(1000)
            }
        }
        Log.d(TAG, "启动服务器状态更新器")
    }

    // 停止服务器状态更新器
    private fun stopServerStatusUpdater() {
        val job = serverStatusJob
        if (job != null) {
            job.cancel()
            serverStatusJob = null
            Log.d(TAG, "停止服务器状态更新器")
        }
    }

    // 处理蓝牙开启请求结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                // 用户同意开启蓝牙，执行核心逻辑
                connectedAncsDevice
            } else {
                // 用户拒绝开启蓝牙，提示
                Toast.makeText(this, "请开启蓝牙以使用ANCS功能", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 处理权限请求结果
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                // 权限全部授予，检查蓝牙是否开启
                checkBluetoothEnabled()
            } else {
                // 权限被拒绝，提示用户
                Toast.makeText(this, "蓝牙权限被拒绝，无法使用ANCS功能", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // 检查蓝牙是否开启，未开启则请求开启
    private fun checkBluetoothEnabled() {
        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 此处理论上不会触发，因为已在checkBluetoothPermissions中检查过权限
                return
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        } else {
            // 蓝牙已开启，执行核心逻辑（如获取已配对设备）
            connectedAncsDevice
        }
    }


    // 请求蓝牙权限
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：请求 BLUETOOTH_SCAN、BLUETOOTH_CONNECT 和 BLUETOOTH_ADVERTISE
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                REQUEST_BLUETOOTH_PERMISSIONS
            )
        } else {
            // Android 6.0-11：请求位置权限
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_BLUETOOTH_PERMISSIONS
            )
        }
    }


    // 检查蓝牙权限是否已授予
    private fun checkBluetoothPermissions(): Boolean {
        // Android 12及以上版本需要BLUETOOTH_SCAN、BLUETOOTH_CONNECT和BLUETOOTH_ADVERTISE权限
        // Android 6.0-11版本需要位置权限（因为旧版蓝牙API依赖位置服务）
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 请求通知详细内容
    @SuppressLint("MissingPermission")
    private fun requestNotificationDetails(notificationUid: Int) {
        val ancsService = bluetoothGatt!!.getService(ANCS_SERVICE_UUID)
        if (ancsService != null) {
            val controlPoint = ancsService.getCharacteristic(CONTROL_POINT_UUID)
            if (controlPoint != null) {
                // 构建获取通知属性的命令
                val command =
                    ByteArray(14) // 修改为14字节：1(命令) + 4(UID) + 3(AppIdentifier) + 3(Title) + 3(Message)
                command[0] =
                    COMMAND_GET_NOTIFICATION_ATTRIBUTES // CommandID for GetNotificationAttributes
                // UID (4 bytes)
                command[1] = (notificationUid and 0xFF).toByte()
                command[2] = ((notificationUid shr 8) and 0xFF).toByte()
                command[3] = ((notificationUid shr 16) and 0xFF).toByte()
                command[4] = ((notificationUid shr 24) and 0xFF).toByte()
                // AppIdentifier
                command[5] = NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER
                command[6] = 0xFF.toByte()
                command[7] = 0xFF.toByte()
                // Title
                command[8] = NOTIFICATION_ATTRIBUTE_TITLE
                command[9] = 0xFF.toByte()
                command[10] = 0xFF.toByte()
                // Message
                command[11] = NOTIFICATION_ATTRIBUTE_MESSAGE
                command[12] = 0xFF.toByte()
                command[13] = 0xFF.toByte()

                controlPoint.setValue(command)
                val success = bluetoothGatt!!.writeCharacteristic(controlPoint)
                Log.d(
                    TAG, "发送获取通知详细内容的请求: $success, command:" + String.format(
                        "%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                        command[0],
                        command[1],
                        command[2],
                        command[3],
                        command[4],
                        command[5],
                        command[6],
                        command[7],
                        command[8],
                        command[9], command[10], command[11], command[12], command[13]
                    )
                )
                if (!success) {
                    Log.e(TAG, "写入特征值失败")
                    runOnUiThread {
                        binding.tvStatus.appendLog("写入特征值失败")
                    }
                }
            }
        }
    }

    // 解析通知详细内容
    private fun parseNotificationDetails(data: ByteArray?) {
        if (data == null || data.size < 5) { // Minimum length: 1 byte Command ID + 4 bytes Notification UID
            Log.e(TAG, "通知详细内容数据长度不足或为null")
            return
        }

        var offset = 0

        // 1. Parse Command ID
        val commandId = data[offset++]
        Log.d(TAG, "Command ID: " + String.format("0x%02X", commandId))

        val notificationUid = (data[offset].toLong() and 0xFFL) or
                ((data[offset + 1].toLong() and 0xFFL) shl 8) or
                ((data[offset + 2].toLong() and 0xFFL) shl 16) or
                ((data[offset + 3].toLong() and 0xFFL) shl 24)
        offset += 4
        Log.d(TAG, "Notification UID: $notificationUid")

        // Variables to store extracted attributes for Toast
        var appIdentifier = "N/A"
        var title = "N/A"
        var message = "N/A"

        // 3. Parse Attribute List (can contain multiple attributes)
        val allDetails = StringBuilder()
        allDetails.append("Notification (UID: ").append(notificationUid).append("):\n")

        while (offset < data.size) {
            if (data.size < offset + 3) { // Need at least 1 byte Attribute ID + 2 bytes Length
                Log.e(TAG, "通知详细内容数据不完整，无法解析更多属性")
                break
            }

            val attributeId = data[offset++].toInt() and 0xFF
            val length =
                (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2

            if (data.size < offset + length) {
                Log.e(TAG, "通知详细内容数据不完整，属性内容长度不足")
                break
            }

            var content: String
            try {
                content = String(data, offset, length, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "解析属性内容时发生编码错误: " + e.message)
                content = "[解码失败]"
            }
            offset += length

            val attributeName = getAttributeName(attributeId)
            val detail = String.format("  - %s: %s", attributeName, content)
            Log.d(TAG, detail)
            allDetails.append(detail).append("\n")

            // Store the relevant attributes for the Toast
            when (attributeId) {
                NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER.toInt() -> appIdentifier = content
                NOTIFICATION_ATTRIBUTE_TITLE.toInt() -> title = content
                NOTIFICATION_ATTRIBUTE_MESSAGE.toInt() -> message = content
            }
        }

        val finalAppIdentifier = appIdentifier
        val finalTitle = title
        val finalMessage = message

        runOnUiThread {
            binding.tvNotifications.appendLog(allDetails.toString())
            val msg = "ANCS消息\n" + String.format(
                "应用: %s\n标题: %s\n内容: %s",
                finalAppIdentifier, finalTitle, finalMessage
            )
            PopNotification.show(msg)
        }
    }

    private fun getAttributeName(attributeId: Int): String {
        return when (attributeId) {
            NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER.toInt() -> "应用"
            NOTIFICATION_ATTRIBUTE_TITLE.toInt() -> "标题"
            NOTIFICATION_ATTRIBUTE_SUBTITLE.toInt() -> "副标题"
            NOTIFICATION_ATTRIBUTE_MESSAGE.toInt() -> "内容"
            NOTIFICATION_ATTRIBUTE_DATE.toInt() -> "时间"
            else -> "未知属性"
        }
    }

    // 执行通知操作（如标记为已读、删除等）
    @SuppressLint("MissingPermission")
    private fun performNotificationAction(notificationUid: Int, actionId: Byte) {
        val ancsService = bluetoothGatt!!.getService(ANCS_SERVICE_UUID)
        if (ancsService != null) {
            val controlPoint = ancsService.getCharacteristic(CONTROL_POINT_UUID)
            if (controlPoint != null) {
                val command = ByteArray(5)
                command[0] = COMMAND_PERFORM_NOTIFICATION_ACTION
                command[1] = (notificationUid and 0xFF).toByte()
                command[2] = ((notificationUid shr 8) and 0xFF).toByte()
                command[3] = ((notificationUid shr 16) and 0xFF).toByte()
                command[4] = actionId

                controlPoint.setValue(command)
                bluetoothGatt!!.writeCharacteristic(controlPoint)
                Log.d(TAG, "已发送通知操作请求")
            }
        }
    }

    // 检查广播权限
    private fun checkAdvertisingPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true // Android 12以下版本不需要此权限
    }

    // 启动连接超时定时器
    private fun startConnectionTimeout() {
        stopConnectionTimeout() // 先停止之前的定时器
        connectionTimeoutJob = lifecycleScope.launch {
            delay(CONNECTION_TIMEOUT)
            handleConnectionTimeout()
        }
        Log.d(TAG, "启动连接超时定时器: " + CONNECTION_TIMEOUT + "ms")
    }

    // 停止连接超时定时器
    private fun stopConnectionTimeout() {
        val job = connectionTimeoutJob
        if (job != null) {
            job.cancel()
            connectionTimeoutJob = null
            Log.d(TAG, "停止连接超时定时器")
        }
    }

    // 处理连接超时
    @SuppressLint("MissingPermission")
    private suspend fun handleConnectionTimeout() {
        if (!isServiceDiscovered && bluetoothGatt != null) {
            val deviceAddress = bluetoothGatt!!.device.address
            Log.w(
                TAG,
                "连接超时，设备 " + deviceAddress + " 在 " + CONNECTION_TIMEOUT + "ms 内未发现ANCS服务"
            )

            runOnUiThread {
                binding.tvStatus.appendLog("连接超时，设备 $deviceAddress 未发现ANCS服务")
            }


            // 记录设备地址
            triedDevices.add(deviceAddress)


            // 断开连接
            bluetoothGatt!!.disconnect()
            bluetoothGatt!!.close()
            bluetoothGatt = null


            // 继续扫描寻找下一个设备
            runOnUiThread {
                binding.tvStatus.appendLog("继续扫描寻找下一个ANCS设备...")
            }


            // 延迟一秒后重新开始扫描
            delay(1000)

            if (!isScanning) {
                startScan()
            }
        }
    }

    companion object {
        private const val TAG = "AncsActivity"

        // ANCS (Apple Notification Center Service) 相关UUID定义
        private val ANCS_SERVICE_UUID: UUID =
            UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        private val NOTIFICATION_SOURCE_UUID: UUID =
            UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        private val CONTROL_POINT_UUID: UUID =
            UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        private val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

        // 标准的客户端特征配置描述符UUID
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 权限请求码
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001

        // 蓝牙开启请求码
        private const val REQUEST_ENABLE_BT = 1002

        // ANCS 命令ID
        private const val COMMAND_GET_NOTIFICATION_ATTRIBUTES: Byte = 0x00
        private const val COMMAND_GET_APP_ATTRIBUTES: Byte = 0x01
        private const val COMMAND_PERFORM_NOTIFICATION_ACTION: Byte = 0x02

        // ANCS 通知属性ID
        private const val NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER: Byte = 0x00
        private const val NOTIFICATION_ATTRIBUTE_TITLE: Byte = 0x01
        private const val NOTIFICATION_ATTRIBUTE_SUBTITLE: Byte = 0x02
        private const val NOTIFICATION_ATTRIBUTE_MESSAGE: Byte = 0x03
        private const val NOTIFICATION_ATTRIBUTE_MESSAGE_SIZE: Byte = 0x04
        private const val NOTIFICATION_ATTRIBUTE_DATE: Byte = 0x05
        private const val NOTIFICATION_ATTRIBUTE_POSITIVE_ACTION_LABEL: Byte = 0x06
        private const val NOTIFICATION_ATTRIBUTE_NEGATIVE_ACTION_LABEL: Byte = 0x07
        private const val SCAN_PERIOD: Long = 10000 // 扫描10秒
        private const val CONNECTION_TIMEOUT: Long = 3000 // 2秒超时
    }
}