package com.wandersnail.bledemo;

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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
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

import java.util.ArrayList;
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
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private LogTextView tvStatus;
    private LogTextView tvNotifications;
    private EditText etMacAddress;
    private Button btnConnect;
    private Button btnScan;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private BluetoothLeScanner bluetoothLeScanner;

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
                    // 连接成功后开始发现服务
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "设备已断开连接");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("设备已断开连接");
                    });
                    gatt.close();
                }
            } else {
                Log.e(TAG, "连接失败，状态码: " + status);
                runOnUiThread(() -> {
                    tvStatus.appendLog("连接失败: " + status);
                });
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

                    // 配置通知源特征，启用通知功能
                    BluetoothGattCharacteristic notificationSource = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID);
                    if (notificationSource != null) {
                        Log.d(TAG, "找到通知源特征，开始配置通知");
                        // 启用通知
                        boolean success = gatt.setCharacteristicNotification(notificationSource, true);
                        if (!success) {
                            Log.e(TAG, "启用通知失败");
                            runOnUiThread(() -> {
                                tvStatus.appendLog("启用通知失败");
                            });
                            return;
                        }

                        BluetoothGattDescriptor descriptor = notificationSource.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            boolean writeSuccess = gatt.writeDescriptor(descriptor);
                            if (!writeSuccess) {
                                Log.e(TAG, "写入通知描述符失败");
                                runOnUiThread(() -> {
                                    tvStatus.appendLog("写入通知描述符失败");
                                });
                                return;
                            }
                            Log.i(TAG, "通知配置完成");
                            runOnUiThread(() -> {
                                tvStatus.appendLog("通知配置完成");
                            });
                        } else {
                            Log.e(TAG, "未找到通知描述符");
                            runOnUiThread(() -> {
                                tvStatus.appendLog("未找到通知描述符");
                            });
                        }
                    } else {
                        Log.e(TAG, "未找到通知源特征");
                        runOnUiThread(() -> {
                            tvStatus.appendLog("未找到通知源特征");
                        });
                    }

                    // 配置数据源特征，启用通知功能
                    BluetoothGattCharacteristic dataSource = ancsService.getCharacteristic(DATA_SOURCE_UUID);
                    if (dataSource != null) {
                        Log.d(TAG, "找到数据源特征，开始配置通知");
                        boolean success = gatt.setCharacteristicNotification(dataSource, true);
                        if (!success) {
                            Log.e(TAG, "启用数据源通知失败");
                            runOnUiThread(() -> {
                                tvStatus.appendLog("启用数据源通知失败");
                            });
                            return;
                        }

                        BluetoothGattDescriptor descriptor = dataSource.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            boolean writeSuccess = gatt.writeDescriptor(descriptor);
                            if (!writeSuccess) {
                                Log.e(TAG, "写入数据源通知描述符失败");
                                runOnUiThread(() -> {
                                    tvStatus.appendLog("写入数据源通知描述符失败");
                                });
                                return;
                            }
                            Log.i(TAG, "数据源通知配置完成");
                            runOnUiThread(() -> {
                                tvStatus.appendLog("数据源通知配置完成");
                            });
                        }
                    }
                } else {
                    Log.e(TAG, "未找到ANCS服务");
                    runOnUiThread(() -> {
                        tvStatus.appendLog("未找到ANCS服务");
                    });
                    gatt.close();
                }
            } else {
                Log.e(TAG, "服务发现失败: " + status);
                runOnUiThread(() -> {
                    tvStatus.appendLog("服务发现失败: " + status);
                });
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
    };



    @SuppressLint("MissingPermission")
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            String deviceName = device.getName();
            if(result.getRssi() < -50) return;
            Log.d(TAG, "发现设备: " + deviceName + " (" + device.getAddress() + "), RSSI: " + rssi);
            // 检查是否有指定的MAC地址
//            String targetMac = etMacAddress.getText().toString().trim();
//            if (!targetMac.isEmpty()) {
//                // 如果输入框有MAC地址，只连接匹配的设备
//                if (!device.getAddress().startsWith(targetMac)) {
//                    return;
//                }
//            }

            // 检查设备是否支持ANCS服务
            if (isAncsDevice(result.getScanRecord())) {
                Log.i(TAG, "找到支持ANCS的设备: " + deviceName + " (" + device.getAddress() + ")");
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

    private boolean isAncsDevice(ScanRecord scanRecord) {
        if (scanRecord == null) return false;
        // 获取设备广播的服务UUID列表
        List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
        // 打印serviceUuids
        Log.d(TAG, "serviceUuids: " + serviceUuids);
        // 检查是否是Apple设备
        byte[] manufacturerData = scanRecord.getManufacturerSpecificData(0x004C); // Apple's company identifier
        if (manufacturerData == null) {
            Log.d(TAG, "不是Apple设备");
            return false;
        }
        Log.d(TAG, "是Apple设备");
        return true;
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

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

        btnScan.setOnClickListener(v -> {
            if (isScanning) {
                stopScan();
                btnScan.setText("扫描");
            } else {
                startScan();
                btnScan.setText("停止");
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

            // 设置扫描超时
            handler.postDelayed(this::stopScan, SCAN_PERIOD);

            isScanning = true;
            if (bluetoothLeScanner == null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
            
            if (bluetoothLeScanner != null) {
                // 优化扫描设置
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                        .setReportDelay(0) // 立即报告结果
                        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT) // 匹配所有广播
                        .build();
                
                // 创建扫描过滤器
                List<ScanFilter> filters = new ArrayList<>();
                // 可以添加特定的过滤器，比如服务UUID
                // filters.add(new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("YOUR-SERVICE-UUID")).build());
                
                bluetoothLeScanner.startScan(filters, settings, scanCallback);
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
        
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
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
        String flags = getEventFlagsString(eventFlags);

        String notification = String.format("通知ID: %d\n类型: %s\n分类: %s\n标志: %s",
                notificationUid, eventType, category, flags);

        runOnUiThread(() -> {
            tvNotifications.appendLog(notification);
        });

        // 如果是新通知，请求详细内容
        if (eventId == 0) { // 0 表示添加新通知
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
            case 2: return "短信";
            case 3: return "邮件";
            case 4: return "日历";
            case 5: return "提醒";
            case 6: return "社交";
            case 7: return "健康";
            case 8: return "游戏";
            case 9: return "其他";
            default: return "未知";
        }
    }

    private String getEventFlagsString(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & 0x01) != 0) sb.append("静音 ");
        if ((flags & 0x02) != 0) sb.append("重要 ");
        if ((flags & 0x04) != 0) sb.append("预存在 ");
        if ((flags & 0x08) != 0) sb.append("正面 ");
        if ((flags & 0x10) != 0) sb.append("负面 ");
        return sb.toString().trim();
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
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
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
            // Android 12+：请求 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
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
        // Android 12及以上版本需要BLUETOOTH_SCAN和BLUETOOTH_CONNECT权限
        // Android 6.0-11版本需要位置权限（因为旧版蓝牙API依赖位置服务）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
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
                byte[] command = new byte[8];
                command[0] = COMMAND_GET_NOTIFICATION_ATTRIBUTES;
                command[1] = (byte) (notificationUid & 0xFF);
                command[2] = (byte) ((notificationUid >> 8) & 0xFF);
                command[3] = (byte) ((notificationUid >> 16) & 0xFF);
                command[4] = (byte) ((notificationUid >> 24) & 0xFF);
                command[5] = NOTIFICATION_ATTRIBUTE_TITLE;
                command[6] = NOTIFICATION_ATTRIBUTE_MESSAGE;
                command[7] = NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER;

                controlPoint.setValue(command);
                bluetoothGatt.writeCharacteristic(controlPoint);
                Log.d(TAG, "已发送获取通知详细内容的请求");
            }
        }
    }

    // 解析通知详细内容
    private void parseNotificationDetails(byte[] data) {
        if (data.length < 4) {
            Log.e(TAG, "通知详细内容数据长度不足");
            return;
        }

        int attributeId = data[0] & 0xFF;
        int length = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        
        if (data.length < 3 + length) {
            Log.e(TAG, "通知详细内容数据不完整");
            return;
        }

        String content = new String(data, 3, length);
        String attributeName = getAttributeName(attributeId);
        
        String detail = String.format("%s: %s", attributeName, content);
        
        runOnUiThread(() -> {
            tvNotifications.appendLog(detail);
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
} 