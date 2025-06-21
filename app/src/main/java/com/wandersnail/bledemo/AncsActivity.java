package com.wandersnail.bledemo;

import static android.text.TextUtils.replace;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.ToastUtils;
import com.kongzue.dialogx.dialogs.PopNotification;

import java.util.List;
import java.util.Map;
import java.util.UUID;


public class AncsActivity extends AppCompatActivity {
    private static final String TAG = "AncsActivity";
    // ANCS (Apple Notification Center Service) 相关UUID定义
    private static final UUID ANCS_SERVICE_UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    private static final UUID NOTIFICATION_SOURCE_UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    private static final UUID CONTROL_POINT_UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    private static final UUID DATA_SOURCE_UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");
    // 标准的客户端特征配置描述符UUID
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // 权限请求码
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    // 蓝牙开启请求码
    private static final int REQUEST_ENABLE_BT = 1002;
    // ANCS 命令ID
    private static final byte COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0x00;
    private static final byte COMMAND_GET_APP_ATTRIBUTES = 0x01;
    private static final byte COMMAND_PERFORM_NOTIFICATION_ACTION = 0x02;

    // ANCS 通知属性ID
    private static final byte NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER = 0x00;
    private static final byte NOTIFICATION_ATTRIBUTE_TITLE = 0x01;
    private static final byte NOTIFICATION_ATTRIBUTE_SUBTITLE = 0x02;
    private static final byte NOTIFICATION_ATTRIBUTE_MESSAGE = 0x03;
    private static final byte NOTIFICATION_ATTRIBUTE_MESSAGE_SIZE = 0x04;
    private static final byte NOTIFICATION_ATTRIBUTE_DATE = 0x05;
    private static final byte NOTIFICATION_ATTRIBUTE_POSITIVE_ACTION_LABEL = 0x06;
    private static final byte NOTIFICATION_ATTRIBUTE_NEGATIVE_ACTION_LABEL = 0x07;
    private static final long SCAN_PERIOD = 10000; // 扫描10秒
    private static final long CONNECTION_TIMEOUT = 3000; // 2秒超时
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private LogTextView tvStatus;
    private LogTextView tvNotifications;
    private EditText etMacAddress;
    private Button btnConnect;
    private Button btnScan;
    private Button btnEnableNotifySource;
    private Button btnEnableDataSource;
    private Button btnToggleServer;
    private TextView tvServerStatus;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGattCharacteristic notificationSourceChar;
    private BluetoothGattCharacteristic dataSourceChar;
    private BleServer bleServer;
    private Runnable serverStatusUpdater;
    
    // 添加连接超时相关变量
    private Runnable connectionTimeoutRunnable;
    private java.util.Set<String> triedDevices = new java.util.HashSet<>(); // 记录已尝试过的设备
    private boolean isServiceDiscovered = false; // 标记是否已发现服务

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: status=" + status + ", newState=" + newState);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "设备已连接，开始发现服务");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("已连接到设备，正在发现服务...");
                    });
                    
                    // 重置服务发现标记
                    isServiceDiscovered = false;
                    
                    // 启动连接超时定时器
                    startConnectionTimeout();
                    
                    // 请求更大的MTU值
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestMtu(512);
                        Log.d(TAG, "已请求MTU: 512");
                    }
                    // 连接成功后开始发现服务
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "设备已断开连接");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("设备已断开连接");
                    });
                    
                    // 停止超时定时器
                    stopConnectionTimeout();
                    
                    gatt.close();
                }
            } else {
                Log.e(TAG, "连接失败，状态码: " + status);
                runOnUiThread(() -> {
                    tvStatus.appendLog("连接失败: " + status);
                });
                
                // 停止超时定时器
                stopConnectionTimeout();
                
                gatt.close();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered: status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService ancsService = gatt.getService(ANCS_SERVICE_UUID);
                if (ancsService != null) {
                    Log.i(TAG, "成功发现ANCS服务");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("发现ANCS服务");
                    });
                    
                    // 标记服务已发现，停止超时定时器
                    isServiceDiscovered = true;
                    stopConnectionTimeout();

                    // 获取通知源特征
                    notificationSourceChar = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID);
                    if (notificationSourceChar != null) {
                        Log.d(TAG, "找到通知源特征");
                        runOnUiThread(() -> {
                            tvStatus.appendLog("找到通知源特征");
                            btnEnableNotifySource.setEnabled(true);
                        });
                    } else {
                        Log.e(TAG, "未找到通知源特征");
                        runOnUiThread(() -> {
                            tvStatus.appendLog("未找到通知源特征");
                            btnEnableNotifySource.setEnabled(false);
                        });
                    }

                    // 获取数据源特征
                    dataSourceChar = ancsService.getCharacteristic(DATA_SOURCE_UUID);
                    if (dataSourceChar != null) {
                        Log.d(TAG, "找到数据源特征");
                        runOnUiThread(() -> {
                            tvStatus.appendLog("找到数据源特征");
                            btnEnableDataSource.setEnabled(true);
                        });
                    } else {
                        Log.e(TAG, "未找到数据源特征");
                        runOnUiThread(() -> {
                            tvStatus.appendLog("未找到数据源特征");
                            btnEnableDataSource.setEnabled(false);
                        });
                    }
                } else {
                    Log.e(TAG, "未找到ANCS服务");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("未找到ANCS服务");
                    });
                    
                    // 记录设备地址并断开连接
                    String deviceAddress = gatt.getDevice().getAddress();
                    triedDevices.add(deviceAddress);
                    Log.d(TAG, "设备 " + deviceAddress + " 不支持ANCS服务，已记录");
                    
                    gatt.close();
                }
            } else {
                Log.e(TAG, "服务发现失败: " + status);
                runOnUiThread(() -> {
                    tvStatus.appendLog("服务发现失败: " + status);
                });
                
                // 记录设备地址并断开连接
                String deviceAddress = gatt.getDevice().getAddress();
                triedDevices.add(deviceAddress);
                Log.d(TAG, "设备 " + deviceAddress + " 服务发现失败，已记录");
                
                gatt.close();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite: status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "描述符写入成功");
            } else {
                Log.e(TAG, "描述符写入失败: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite: status=" + status + ", UUID=" + characteristic.getUuid());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "特征值写入成功");
                runOnUiThread(() -> {
                    tvStatus.appendLog("特征值写入成功");
                });
            } else {
                Log.e(TAG, "特征值写入失败: " + status);
                runOnUiThread(() -> {
                    tvStatus.appendLog("特征值写入失败: " + status);
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // 处理接收到的ANCS通知数据
            if (characteristic.getUuid().equals(NOTIFICATION_SOURCE_UUID)) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    Log.d(TAG, "收到ANCS通知数据，长度: " + data.length);
                    parseAncsNotification(data);
                }
            } else if (characteristic.getUuid().equals(DATA_SOURCE_UUID)) {
                // 处理通知详细内容
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    Log.d(TAG, "收到通知详细内容，长度: " + data.length);
                    parseNotificationDetails(data);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU协商成功: " + mtu);
                runOnUiThread(() -> {
                    tvStatus.appendLog("MTU协商成功: " + mtu);
                });
            } else {
                Log.e(TAG, "MTU协商失败: " + status);
                runOnUiThread(() -> {
                    tvStatus.appendLog("MTU协商失败: " + status);
                });
            }
        }
    };



    @SuppressLint("MissingPermission")
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            String deviceName = device.getName();
            String deviceAddress = device.getAddress();
            
//            if(result.getRssi() < -40) return;
            Log.d(TAG, "发现设备: " + deviceName + " (" + deviceAddress + "), RSSI: " + rssi);
            
            // 检查是否已经尝试过这个设备
            if (triedDevices.contains(deviceAddress)) {
                Log.d(TAG, "设备 " + deviceAddress + " 已尝试过，跳过");
                return;
            }
            

            // 检查设备名称是否包含"ancs"（不区分大小写）
            if (deviceName != null && deviceName.toLowerCase().contains("ancs")) {
                Log.i(TAG, "找到名称包含ANCS的设备: " + deviceName + " (" + deviceAddress + ")");
                // 记录设备地址，防止重复尝试
                triedDevices.add(deviceAddress);
                stopScan();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "扫描失败，错误码: " + errorCode);
            runOnUiThread(() -> {
                tvStatus.appendLog("扫描失败: " + errorCode);
            });
            isScanning = false;
            btnScan.setText("扫描");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ancs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("ANCS");
        }

        tvStatus = findViewById(R.id.tvStatus);
        tvNotifications = findViewById(R.id.tvNotifications);
        etMacAddress = findViewById(R.id.etMacAddress);
        btnConnect = findViewById(R.id.btnConnect);
        btnScan = findViewById(R.id.btnScan);
        btnEnableNotifySource = findViewById(R.id.btnEnableNotifySource);
        btnEnableDataSource = findViewById(R.id.btnEnableDataSource);
        btnToggleServer = findViewById(R.id.btnToggleServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);

        btnScan.setOnClickListener(v -> {
            if (isScanning) {
                stopScan();
                btnScan.setText("扫描");
            } else {
                startScan();
                btnScan.setText("停止");
            }
        });

        btnEnableNotifySource.setOnClickListener(v -> {
            if (bluetoothGatt != null && notificationSourceChar != null) {
                enableNotificationSource();
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            }
        });

        btnEnableDataSource.setOnClickListener(v -> {
            if (bluetoothGatt != null && dataSourceChar != null) {
                enableDataSource();
            } else {
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show();
            }
        });

        btnConnect.setOnClickListener(v -> {
            String macAddress = etMacAddress.getText().toString().trim();
            if (macAddress.isEmpty()) {
                Toast.makeText(this, "请输入MAC地址", Toast.LENGTH_SHORT).show();
                return;
            }
            stopScan();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
            if (device != null) {
                connectToDevice(device);
            } else {
                Toast.makeText(this, "无效的MAC地址", Toast.LENGTH_SHORT).show();
            }
        });

        btnToggleServer.setOnClickListener(v -> {
            if (bleServer == null) {
                // 创建并启动BLE服务器
                bleServer = new BleServer(this);
                boolean success = bleServer.startServer();
                if (success) {
                    btnToggleServer.setText("停止BLE服务器");
                    tvServerStatus.setText("服务器状态: 运行中");
                    tvStatus.appendLog("BLE服务器启动成功");
                    Toast.makeText(this, "BLE服务器启动成功", Toast.LENGTH_SHORT).show();
                    
                    // 启动状态更新器
                    startServerStatusUpdater();
                } else {
                    tvStatus.appendLog("BLE服务器启动失败");
                    Toast.makeText(this, "BLE服务器启动失败", Toast.LENGTH_SHORT).show();
                    bleServer = null;
                }
            } else {
                // 停止BLE服务器
                bleServer.stopServer();
                btnToggleServer.setText("启动BLE服务器");
                tvServerStatus.setText("服务器状态: 已停止");
                tvStatus.appendLog("BLE服务器已停止");
                Toast.makeText(this, "BLE服务器已停止", Toast.LENGTH_SHORT).show();
                
                // 停止状态更新器
                stopServerStatusUpdater();
                bleServer = null;
            }
        });

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 检查并请求权限
        if (checkBluetoothPermissions()) {
            // 权限已授予，检查蓝牙是否开启
            checkBluetoothEnabled();
        } else {
            // 请求权限
            requestBluetoothPermissions();
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice getConnectedAncsDevice() {
        // 获取所有已连接的设备
        Log.d(TAG, "开始查找已连接的ANCS设备");
        
        // 首先检查已配对的设备
        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                Log.i(TAG, "找到LE设备: " + device.getAddress());
                return device;
            }
        }
        
        // 如果没有找到已配对的设备，提示用户开始扫描
        Log.w(TAG, "未找到已连接的ANCS设备");
        runOnUiThread(() -> {
            tvStatus.appendLog("未找到已连接的ANCS设备，请点击扫描按钮开始扫描");
        });
        return null;
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!isScanning) {
            runOnUiThread(() -> {
                tvStatus.appendLog("正在扫描BLE设备...");
            });

            // 清空已尝试设备的记录，重新开始尝试
            triedDevices.clear();
            Log.d(TAG, "清空已尝试设备记录，重新开始扫描");

            // 设置扫描超时
            handler.postDelayed(this::stopScan, SCAN_PERIOD);

            isScanning = true;
            if (bluetoothLeScanner == null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
            
            if (bluetoothLeScanner != null) {
                // 优化扫描设置
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 低延迟模式(低功耗)
                        .setReportDelay(0) // 立即报告结果
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // 匹配所有广播
                        .build();
                
                // 不设置过滤器，扫描所有设备，在回调中根据名称过滤
                bluetoothLeScanner.startScan(null, settings, scanCallback);
            } else {
                Log.e(TAG, "无法获取BluetoothLeScanner");
                runOnUiThread(() -> {
                    tvStatus.appendLog("无法启动扫描");
                });
                isScanning = false;
                btnScan.setText("扫描");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
            runOnUiThread(() -> {
                tvStatus.appendLog("扫描结束");
                btnScan.setText("扫描");
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        Log.i(TAG, "开始连接设备: " + device.getAddress());
        runOnUiThread(() -> {
            tvStatus.appendLog("正在连接到设备...");
        });
        
        // 如果设备未配对，先配对
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            device.createBond();
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback,BluetoothDevice.TRANSPORT_LE);
    }


    @SuppressLint("MissingPermission")
    private void enableDataSource() {
        if (dataSourceChar != null) {
            // 启用通知
            boolean success = bluetoothGatt.setCharacteristicNotification(dataSourceChar, true);
            if (!success) {
                Log.e(TAG, "启用数据源通知失败");
                runOnUiThread(() -> {
                    tvStatus.appendLog("启用数据源通知失败");
                });
                return;
            }

            BluetoothGattDescriptor descriptor = dataSourceChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean writeSuccess = bluetoothGatt.writeDescriptor(descriptor);
                if (!writeSuccess) {
                    Log.e(TAG, "写入数据源描述符失败");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("写入数据源描述符失败");
                    });
                    return;
                }
                Log.i(TAG, "数据源配置完成");
                runOnUiThread(() -> {
                    tvStatus.appendLog("数据源配置完成");
                    btnEnableDataSource.setEnabled(false);
                });
            }
        }
    }


    @SuppressLint("MissingPermission")
    private void enableNotificationSource() {
        if (notificationSourceChar != null) {
            // 启用通知
            boolean success = bluetoothGatt.setCharacteristicNotification(notificationSourceChar, true);
            if (!success) {
                Log.e(TAG, "启用通知源通知失败");
                runOnUiThread(() -> {
                    tvStatus.appendLog("启用通知源通知失败");
                });
                return;
            }

            BluetoothGattDescriptor descriptor = notificationSourceChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean writeSuccess = bluetoothGatt.writeDescriptor(descriptor);
                if (!writeSuccess) {
                    Log.e(TAG, "写入通知源描述符失败");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("写入通知源描述符失败");
                    });
                    return;
                }
                Log.i(TAG, "通知源配置完成");
                runOnUiThread(() -> {
                    tvStatus.appendLog("通知源配置完成");
                    btnEnableNotifySource.setEnabled(false);
                });
            }
        }
    }

    private void parseAncsNotification(byte[] data) {
        // 解析ANCS通知数据
        // 数据格式：
        // - 字节0: 事件ID
        // - 字节1: 事件标志
        // - 字节2: 类别ID
        // - 字节3: 类别计数
        // - 字节4-7: 通知UID
        if (data.length < 8) {
            Log.e(TAG, "通知数据长度不足: " + data.length);
            return;
        }

        int eventId = data[0] & 0xFF;
        int eventFlags = data[1] & 0xFF;
        int categoryId = data[2] & 0xFF;
        int categoryCount = data[3] & 0xFF;
        int notificationUid = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8) | ((data[6] & 0xFF) << 16) | ((data[7] & 0xFF) << 24);

        Log.d(TAG, String.format("解析通知数据: eventId=%d, flags=%d, categoryId=%d, uid=%d",
                eventId, eventFlags, categoryId, notificationUid));

        String eventType = getEventTypeString(eventId);
        String category = getCategoryString(categoryId);

        @SuppressLint("DefaultLocale") String notification = String.format("通知ID: %d\n类型: %s\n分类: %s\n",
                notificationUid, eventType, category);

        runOnUiThread(() -> {
            tvNotifications.appendLog(notification);
        });

        // 如果是新通知，请求详细内容
        if (eventId == 0 && categoryId == 4) { // 0 表示添加新通知
            requestNotificationDetails(notificationUid);
        }
    }

    private String getEventTypeString(int eventId) {
        switch (eventId) {
            case 0: return "添加";
            case 1: return "修改";
            case 2: return "删除";
            default: return "未知";
        }
    }

    private String getCategoryString(int categoryId) {
        switch (categoryId) {
            case 0: return "其他";
            case 1: return "来电";
            case 2: return "未接来电";
            case 3: return "语音邮件";
            case 4: return "社交";
            case 5: return "日程";
            case 6: return "邮件";
            case 7: return "新闻";
            case 8: return "健康与健身";
            case 9: return "商业/金融";
            case 10: return "位置";
            case 11: return "娱乐";
            default: return "未知类别";
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        // 停止连接超时定时器
        stopConnectionTimeout();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
        }
        // 停止BLE服务器
        if (bleServer != null) {
            bleServer.stopServer();
            bleServer = null;
        }
        // 停止状态更新器
        stopServerStatusUpdater();
    }

    // 启动服务器状态更新器
    private void startServerStatusUpdater() {
        if (serverStatusUpdater == null) {
            serverStatusUpdater = new Runnable() {
                @Override
                public void run() {
                    if (bleServer != null && bleServer.isServerRunning()) {
                        int heartbeatCount = bleServer.getHeartbeatCounter();
                        int deviceCount = bleServer.getConnectedDeviceCount();
                        boolean isAdvertising = bleServer.isAdvertising();
                        String status = String.format("服务器状态: 运行中 (心跳: %d, 设备: %d, 广播: %s)", 
                                heartbeatCount, deviceCount, isAdvertising ? "开启" : "关闭");
                        tvServerStatus.setText(status);
                        // 每秒更新一次
                        handler.postDelayed(this, 1000);
                    }
                }
            };
        }
        handler.post(serverStatusUpdater);
    }

    // 停止服务器状态更新器
    private void stopServerStatusUpdater() {
        if (serverStatusUpdater != null) {
            handler.removeCallbacks(serverStatusUpdater);
        }
    }

    // 处理蓝牙开启请求结果
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                // 用户同意开启蓝牙，执行核心逻辑
                getConnectedAncsDevice();
            } else {
                // 用户拒绝开启蓝牙，提示
                Toast.makeText(this, "请开启蓝牙以使用ANCS功能", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 处理权限请求结果
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                // 权限全部授予，检查蓝牙是否开启
                checkBluetoothEnabled();
            } else {
                // 权限被拒绝，提示用户
                Toast.makeText(this, "蓝牙权限被拒绝，无法使用ANCS功能", Toast.LENGTH_SHORT).show();
            }
        }
    }


    // 检查蓝牙是否开启，未开启则请求开启
    private void checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // 此处理论上不会触发，因为已在checkBluetoothPermissions中检查过权限
                return;
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            // 蓝牙已开启，执行核心逻辑（如获取已配对设备）
            getConnectedAncsDevice();
        }
    }


    // 请求蓝牙权限
    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：请求 BLUETOOTH_SCAN、BLUETOOTH_CONNECT 和 BLUETOOTH_ADVERTISE
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                    },
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        } else {
            // Android 6.0-11：请求位置权限
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        }
    }


    // 检查蓝牙权限是否已授予
    private boolean checkBluetoothPermissions() {
        // Android 12及以上版本需要BLUETOOTH_SCAN、BLUETOOTH_CONNECT和BLUETOOTH_ADVERTISE权限
        // Android 6.0-11版本需要位置权限（因为旧版蓝牙API依赖位置服务）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    // 请求通知详细内容
    @SuppressLint("MissingPermission")
    private void requestNotificationDetails(int notificationUid) {
        BluetoothGattService ancsService = bluetoothGatt.getService(ANCS_SERVICE_UUID);
        if (ancsService != null) {
            BluetoothGattCharacteristic controlPoint = ancsService.getCharacteristic(CONTROL_POINT_UUID);
            if (controlPoint != null) {
                // 构建获取通知属性的命令
                byte[] command = new byte[14];  // 修改为14字节：1(命令) + 4(UID) + 3(AppIdentifier) + 3(Title) + 3(Message)
                command[0] = COMMAND_GET_NOTIFICATION_ATTRIBUTES;  // CommandID for GetNotificationAttributes
                // UID (4 bytes)
                command[1] = (byte) (notificationUid & 0xFF);
                command[2] = (byte) ((notificationUid >> 8) & 0xFF);
                command[3] = (byte) ((notificationUid >> 16) & 0xFF);
                command[4] = (byte) ((notificationUid >> 24) & 0xFF);
                // AppIdentifier
                command[5] = NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER;
                command[6] = (byte) 0xFF;
                command[7] = (byte) 0xFF;
                // Title
                command[8] = NOTIFICATION_ATTRIBUTE_TITLE;
                command[9] = (byte) 0xFF;
                command[10] = (byte) 0xFF;
                // Message
                command[11] = NOTIFICATION_ATTRIBUTE_MESSAGE;
                command[12] = (byte) 0xFF;
                command[13] = (byte) 0xFF;

                controlPoint.setValue(command);
                boolean success = bluetoothGatt.writeCharacteristic(controlPoint);
                Log.d(TAG, "发送获取通知详细内容的请求: " + success + ", command:" + String.format("%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                        command[0], command[1], command[2], command[3], command[4], command[5], command[6], command[7],
                        command[8], command[9], command[10], command[11], command[12], command[13]));
                if (!success) {
                    Log.e(TAG, "写入特征值失败");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("写入特征值失败");
                    });
                }
            }
        }
    }

    // 解析通知详细内容
    private void parseNotificationDetails(byte[] data) {
        if (data == null || data.length < 5) { // Minimum length: 1 byte Command ID + 4 bytes Notification UID
            Log.e(TAG, "通知详细内容数据长度不足或为null");
            return;
        }

        int offset = 0;

        // 1. Parse Command ID
        byte commandId = data[offset++];
        Log.d(TAG, "Command ID: " + String.format("0x%02X", commandId));

        long notificationUid = (data[offset] & 0xFFL) |
                ((data[offset + 1] & 0xFFL) << 8) |
                ((data[offset + 2] & 0xFFL) << 16) |
                ((data[offset + 3] & 0xFFL) << 24);
        offset += 4;
        Log.d(TAG, "Notification UID: " + notificationUid);

        // Variables to store extracted attributes for Toast
        String appIdentifier = "N/A";
        String title = "N/A";
        String message = "N/A";

        // 3. Parse Attribute List (can contain multiple attributes)
        final StringBuilder allDetails = new StringBuilder();
        allDetails.append("Notification (UID: ").append(notificationUid).append("):\n");

        while (offset < data.length) {
            if (data.length < offset + 3) { // Need at least 1 byte Attribute ID + 2 bytes Length
                Log.e(TAG, "通知详细内容数据不完整，无法解析更多属性");
                break;
            }

            int attributeId = data[offset++] & 0xFF;
            int length = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            offset += 2;

            if (data.length < offset + length) {
                Log.e(TAG, "通知详细内容数据不完整，属性内容长度不足");
                break;
            }

            String content;
            try {
                content = new String(data, offset, length, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.e(TAG, "解析属性内容时发生编码错误: " + e.getMessage());
                content = "[解码失败]";
            }
            offset += length;

            String attributeName = getAttributeName(attributeId);
            String detail = String.format("  - %s: %s", attributeName, content);
            Log.d(TAG, detail);
            allDetails.append(detail).append("\n");

            // Store the relevant attributes for the Toast
            switch (attributeId) {
                case NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER:
                    appIdentifier = content;
                    break;
                case NOTIFICATION_ATTRIBUTE_TITLE:
                    title = content;
                    break;
                case NOTIFICATION_ATTRIBUTE_MESSAGE:
                    message = content;
                    break;
            }
        }

        final String finalAppIdentifier = appIdentifier;
        final String finalTitle = title;
        final String finalMessage = message;

        runOnUiThread(() -> {
            tvNotifications.appendLog(allDetails.toString());
            String msg = "ANCS消息\n" + String.format("应用: %s\n标题: %s\n内容: %s",
                    finalAppIdentifier, finalTitle, finalMessage);
            PopNotification.show(msg);

        });
    }

    private String getAttributeName(int attributeId) {
        switch (attributeId) {
            case NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER:
                return "应用";
            case NOTIFICATION_ATTRIBUTE_TITLE:
                return "标题";
            case NOTIFICATION_ATTRIBUTE_SUBTITLE:
                return "副标题";
            case NOTIFICATION_ATTRIBUTE_MESSAGE:
                return "内容";
            case NOTIFICATION_ATTRIBUTE_DATE:
                return "时间";
            default:
                return "未知属性";
        }
    }

    // 执行通知操作（如标记为已读、删除等）
    @SuppressLint("MissingPermission")
    private void performNotificationAction(int notificationUid, byte actionId) {
        BluetoothGattService ancsService = bluetoothGatt.getService(ANCS_SERVICE_UUID);
        if (ancsService != null) {
            BluetoothGattCharacteristic controlPoint = ancsService.getCharacteristic(CONTROL_POINT_UUID);
            if (controlPoint != null) {
                byte[] command = new byte[5];
                command[0] = COMMAND_PERFORM_NOTIFICATION_ACTION;
                command[1] = (byte) (notificationUid & 0xFF);
                command[2] = (byte) ((notificationUid >> 8) & 0xFF);
                command[3] = (byte) ((notificationUid >> 16) & 0xFF);
                command[4] = actionId;

                controlPoint.setValue(command);
                bluetoothGatt.writeCharacteristic(controlPoint);
                Log.d(TAG, "已发送通知操作请求");
            }
        }
    }

    // 检查广播权限
    private boolean checkAdvertisingPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Android 12以下版本不需要此权限
    }
    
    // 启动连接超时定时器
    private void startConnectionTimeout() {
        stopConnectionTimeout(); // 先停止之前的定时器
        connectionTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                handleConnectionTimeout();
            }
        };
        handler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT);
        Log.d(TAG, "启动连接超时定时器: " + CONNECTION_TIMEOUT + "ms");
    }
    
    // 停止连接超时定时器
    private void stopConnectionTimeout() {
        if (connectionTimeoutRunnable != null) {
            handler.removeCallbacks(connectionTimeoutRunnable);
            connectionTimeoutRunnable = null;
            Log.d(TAG, "停止连接超时定时器");
        }
    }
    
    // 处理连接超时
    @SuppressLint("MissingPermission")
    private void handleConnectionTimeout() {
        if (!isServiceDiscovered && bluetoothGatt != null) {
            String deviceAddress = bluetoothGatt.getDevice().getAddress();
            Log.w(TAG, "连接超时，设备 " + deviceAddress + " 在 " + CONNECTION_TIMEOUT + "ms 内未发现ANCS服务");
            
            runOnUiThread(() -> {
                tvStatus.appendLog("连接超时，设备 " + deviceAddress + " 未发现ANCS服务");
            });
            
            // 记录设备地址
            triedDevices.add(deviceAddress);
            
            // 断开连接
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
            
            // 继续扫描寻找下一个设备
            runOnUiThread(() -> {
                tvStatus.appendLog("继续扫描寻找下一个ANCS设备...");
            });
            
            // 延迟一秒后重新开始扫描
            handler.postDelayed(() -> {
                if (!isScanning) {
                    startScan();
                }
            }, 1000);
        }
    }
} 