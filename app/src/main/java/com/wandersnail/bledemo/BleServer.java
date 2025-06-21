package com.wandersnail.bledemo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class BleServer {
    private static final String TAG = "BleServer";

    // 使用更标准的UUID格式，便于识别
    private static final UUID HEARTBEAT_SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    // 心跳特征UUID
    private static final UUID HEARTBEAT_CHARACTERISTIC_UUID = UUID.fromString("0000FFE1-0000-1000-8000-123456789ABC");
    // 客户端特征配置描述符UUID (标准UUID)
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothGattService heartbeatService;
    private BluetoothGattCharacteristic heartbeatCharacteristic;
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    private Handler handler;
    private boolean isServerRunning = false;
    private boolean isAdvertising = false;
    private boolean isUsingFallback = false;
    private int heartbeatCounter = 0;
    private List<android.bluetooth.BluetoothDevice> connectedDevices = new ArrayList<>();
    private List<android.bluetooth.BluetoothDevice> subscribedDevices = new ArrayList<>();

    // 心跳发送间隔（毫秒）
    private static final long HEARTBEAT_INTERVAL = 1000; // 1秒

    public BleServer(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    /**
     * 启动BLE服务器
     */
    @SuppressLint("MissingPermission")
    public boolean startServer() {
        if (isServerRunning) {
            Log.w(TAG, "BLE服务器已在运行.");
            return true;
        }

        if (bluetoothManager == null) {
            Log.e(TAG, "BluetoothManager不可用，无法启动BLE服务器.");
            return false;
        }

        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "蓝牙适配器不可用或未启用，无法启动BLE服务器.");
            return false;
        }

        // 创建GATT服务器
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            Log.e(TAG, "无法创建GATT服务器.");
            return false;
        }

        // 创建心跳服务
        heartbeatService = new BluetoothGattService(
                HEARTBEAT_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // 创建心跳特征
        // 增加 PROPERTY_READ 和 PERMISSION_READ，允许客户端读取当前心跳值
        heartbeatCharacteristic = new BluetoothGattCharacteristic(
                HEARTBEAT_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ, // 允许通知和读取
                BluetoothGattCharacteristic.PERMISSION_READ // 允许读取权限
        );

        // 添加客户端特征配置描述符
        // 允许读写权限，以便客户端启用/禁用通知
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        );
        heartbeatCharacteristic.addDescriptor(descriptor);

        // 将特征添加到服务
        heartbeatService.addCharacteristic(heartbeatCharacteristic);

        // 将服务添加到GATT服务器
        boolean success = gattServer.addService(heartbeatService);
        if (success) {
            isServerRunning = true;
            Log.i(TAG, "BLE服务器启动成功.");
            Log.i(TAG, "服务UUID: " + HEARTBEAT_SERVICE_UUID.toString());
            Log.i(TAG, "特征UUID: " + HEARTBEAT_CHARACTERISTIC_UUID.toString());

            // 启动BLE广播
            startAdvertising();

            // 开始发送心跳
            startHeartbeat();
            return true;
        } else {
            Log.e(TAG, "添加服务失败，关闭GATT服务器.");
            gattServer.close();
            gattServer = null;
            return false;
        }
    }

    /**
     * 启动BLE广播
     */
    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        // 检查广播权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少BLUETOOTH_ADVERTISE权限，无法启动BLE广播.");
                return;
            }
        }

        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter不可用，无法启动BLE广播.");
            return;
        }

        bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser不可用，您的设备可能不支持BLE广播.");
            return;
        }

        // 配置广播设置 - 使用平衡模式提高兼容性
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // 平衡模式，提高兼容性
                .setConnectable(true) // 保持可连接
                .setTimeout(0) // 0表示一直广播
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // 高发射功率
                .build();

        // 配置广播数据 - 包含服务UUID和设备名称
        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HEARTBEAT_SERVICE_UUID))
                .setIncludeDeviceName(true) // 包含设备名称，提高发现率
                .build();

        // 配置扫描响应数据 - 提供额外的设备信息
        AdvertiseData scanResponseData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HEARTBEAT_SERVICE_UUID))
                .setIncludeTxPowerLevel(true) // 包含发射功率信息
                .build();

        // 开始广播，包含扫描响应数据
        bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback);
        Log.i(TAG, "尝试启动BLE广播...");
        Log.d(TAG, "广播设置: " + settings.toString());
        Log.d(TAG, "广播数据: " + advertiseData.toString());
        Log.d(TAG, "扫描响应数据: " + scanResponseData.toString());
    }

    /**
     * 启动BLE广播（备用方法 - 针对数据过大或某些兼容性问题）
     */
    @SuppressLint("MissingPermission")
    private void startAdvertisingFallback() {
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter不可用，无法启动备用BLE广播.");
            return;
        }

        bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser不可用，无法启动备用BLE广播.");
            return;
        }

        // 更保守的广播配置，提高兼容性
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) // 低功耗模式，更稳定
                .setConnectable(true) // 保持可连接
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM) // 中等发射功率
                .build();

        // 最小化的广播数据，只包含服务UUID
        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HEARTBEAT_SERVICE_UUID))
                .build();

        // 简单的扫描响应数据
        AdvertiseData scanResponseData = new AdvertiseData.Builder()
                .setIncludeDeviceName(true) // 在扫描响应中包含设备名称
                .build();

        bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback);
        Log.i(TAG, "尝试启动BLE广播（备用方法）...");
        Log.d(TAG, "备用广播设置: " + settings.toString());
        Log.d(TAG, "备用广播数据: " + advertiseData.toString());
        Log.d(TAG, "备用扫描响应数据: " + scanResponseData.toString());
        isUsingFallback = true;
    }

    /**
     * 启动BLE广播（兼容性模式 - 针对老旧设备）
     */
    @SuppressLint("MissingPermission")
    private void startAdvertisingCompatibility() {
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter不可用，无法启动兼容性BLE广播.");
            return;
        }

        bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser不可用，无法启动兼容性BLE广播.");
            return;
        }

        // 最兼容的广播配置
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) // 低功耗模式，最兼容
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW) // 低发射功率，减少干扰
                .build();

        // 只包含最基本的广播数据
        AdvertiseData advertiseData = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HEARTBEAT_SERVICE_UUID))
                .build();

        // 不包含扫描响应数据，减少复杂性
        bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, null, advertiseCallback);
        Log.i(TAG, "尝试启动BLE广播（兼容性模式）...");
        Log.d(TAG, "兼容性广播设置: " + settings.toString());
        Log.d(TAG, "兼容性广播数据: " + advertiseData.toString());
    }

    /**
     * 停止BLE广播
     */
    @SuppressLint("MissingPermission")
    private void stopAdvertising() {
        if (bluetoothLeAdvertiser != null && isAdvertising) {
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            isAdvertising = false;
            Log.i(TAG, "BLE广播已停止.");
        } else if (bluetoothLeAdvertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser已为null，无法停止广播.");
        } else {
            Log.i(TAG, "BLE广播未在运行，无需停止.");
        }
    }

    /**
     * 停止BLE服务器
     */
    @SuppressLint("MissingPermission")
    public void stopServer() {
        if (!isServerRunning) {
            Log.w(TAG, "BLE服务器未在运行，无需停止.");
            return;
        }

        // 停止心跳
        stopHeartbeat();

        // 停止广播
        stopAdvertising();

        // 重置标志
        isUsingFallback = false;

        // 清理连接的设备列表
        connectedDevices.clear();
        subscribedDevices.clear();

        // 关闭GATT服务器
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
            Log.i(TAG, "GATT服务器已关闭.");
        }

        isServerRunning = false;
        Log.i(TAG, "BLE服务器已完全停止.");
    }

    /**
     * 开始发送心跳
     */
    private void startHeartbeat() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isServerRunning) {
                    sendHeartbeat();
                    // 继续下一次心跳
                    handler.postDelayed(this, HEARTBEAT_INTERVAL);
                }
            }
        }, HEARTBEAT_INTERVAL);
        Log.i(TAG, "心跳发送已启动，间隔: " + HEARTBEAT_INTERVAL + "ms.");
    }

    /**
     * 停止发送心跳
     */
    private void stopHeartbeat() {
        handler.removeCallbacksAndMessages(null);
        Log.i(TAG, "心跳发送已停止.");
    }

    /**
     * 发送心跳数据
     */
    private void sendHeartbeat() {
        if (gattServer == null || heartbeatCharacteristic == null) {
            Log.w(TAG, "GATT服务器或心跳特征未初始化，无法发送心跳.");
            return;
        }

        heartbeatCounter++;
        String heartbeatData = "Heartbeat: " + heartbeatCounter;
        byte[] data = heartbeatData.getBytes();
        heartbeatCharacteristic.setValue(data);

        if (subscribedDevices.isEmpty()) {
            Log.d(TAG, "没有客户端订阅心跳通知，跳过发送.");
            return;
        }

        boolean allSuccess = true;
        for (android.bluetooth.BluetoothDevice device : subscribedDevices) {
            // notifyCharacteristicChanged 方法用于向已订阅的客户端发送通知
            boolean success = gattServer.notifyCharacteristicChanged(
                    device,
                    heartbeatCharacteristic,
                    false // 不需要确认 (Indication 为 true)
            );

            if (success) {
                Log.d(TAG, "心跳通知发送成功到设备 " + device.getAddress() + ": " + heartbeatData);
            } else {
                Log.e(TAG, "心跳通知发送失败到设备 " + device.getAddress());
                allSuccess = false;
            }
        }

        if (allSuccess) {
            Log.d(TAG, "所有订阅设备的心跳通知发送完成.");
        } else {
            Log.e(TAG, "部分心跳通知发送失败.");
        }
    }

    /**
     * 检查服务器是否正在运行
     */
    public boolean isServerRunning() {
        return isServerRunning;
    }

    /**
     * 获取心跳计数器
     */
    public int getHeartbeatCounter() {
        return heartbeatCounter;
    }

    /**
     * 获取连接的设备数量
     */
    public int getConnectedDeviceCount() {
        return connectedDevices.size();
    }

    /**
     * 获取订阅通知的设备数量
     */
    public int getSubscribedDeviceCount() {
        return subscribedDevices.size();
    }

    /**
     * 检查是否正在广播
     */
    public boolean isAdvertising() {
        return isAdvertising;
    }

    /**
     * 广播回调
     */
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            isAdvertising = true;
            isUsingFallback = false; // 如果成功，重置备用标志
            Log.i(TAG, "BLE广播启动成功!");
            Log.d(TAG, "实际广播设置: " + settingsInEffect.toString());
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            isAdvertising = false;
            String errorMessage;
            boolean shouldRetry = false;
            
            switch (errorCode) {
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    errorMessage = "广播已启动.";
                    Log.w(TAG, "广播已启动，无需重复启动.");
                    return; // 直接返回，不报告失败
                    
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    errorMessage = "广播数据太大.";
                    if (!isUsingFallback) {
                        Log.w(TAG, "广播数据太大，尝试使用备用广播方法...");
                        startAdvertisingFallback();
                        return; // 尝试备用方法后直接返回，不报告失败
                    } else {
                        Log.w(TAG, "备用广播方法也失败，尝试兼容性模式...");
                        startAdvertisingCompatibility();
                        return;
                    }
                    
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    errorMessage = "广播器过多，系统资源不足.";
                    shouldRetry = true;
                    break;
                    
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    errorMessage = "内部错误.";
                    shouldRetry = true;
                    break;
                    
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    errorMessage = "设备不支持此广播功能.";
                    Log.e(TAG, "设备不支持BLE广播功能，请检查设备兼容性.");
                    break;
                    
                default:
                    errorMessage = "未知错误 (错误码: " + errorCode + ").";
                    shouldRetry = true;
                    break;
            }
            
            Log.e(TAG, "BLE广播启动失败: " + errorMessage);
            
            // 如果应该重试，延迟后重试
            if (shouldRetry && !isUsingFallback) {
                Log.i(TAG, "将在3秒后重试广播...");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isServerRunning && !isAdvertising) {
                            Log.i(TAG, "重试启动BLE广播...");
                            startAdvertising();
                        }
                    }
                }, 3000);
            }
        }
    };

    /**
     * GATT服务器回调
     */
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(android.bluetooth.BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "客户端已连接: " + device.getAddress());
                    if (!connectedDevices.contains(device)) {
                        connectedDevices.add(device);
                    }
                    // 连接成功后，客户端需要立即开始服务发现
                    Log.d(TAG, "连接的设备数量: " + connectedDevices.size());
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "客户端已断开连接: " + device.getAddress());
                    connectedDevices.remove(device);
                    subscribedDevices.remove(device); // 客户端断开连接，移除订阅
                    Log.d(TAG, "连接的设备数量: " + connectedDevices.size() + ", 订阅的设备数量: " + subscribedDevices.size());
                }
            } else {
                Log.e(TAG, "连接状态改变失败，设备: " + device.getAddress() + ", 状态: " + status + ", 新状态: " + newState);
                // 某些错误状态下，也需要从列表中移除设备
                connectedDevices.remove(device);
                subscribedDevices.remove(device);
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            super.onServiceAdded(status, service);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "服务添加成功: " + service.getUuid());
            } else {
                Log.e(TAG, "服务添加失败: " + service.getUuid() + ", 状态: " + status);
            }
        }

        @Override
        public void onCharacteristicReadRequest(android.bluetooth.BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);

            Log.d(TAG, "收到来自设备 " + device.getAddress() + " 的特征读取请求: " + characteristic.getUuid());

            if (characteristic.getUuid().equals(HEARTBEAT_CHARACTERISTIC_UUID)) {
                // 返回当前心跳数据
                String heartbeatData = "Heartbeat: " + heartbeatCounter;
                byte[] data = heartbeatData.getBytes();

                // 检查offset，避免发送部分数据
                if (offset > data.length) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    Log.e(TAG, "读取请求 offset 超出数据长度.");
                    return;
                }

                byte[] responseData = new byte[data.length - offset];
                System.arraycopy(data, offset, responseData, 0, responseData.length);

                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData);
                Log.d(TAG, "响应心跳读取请求到设备 " + device.getAddress() + ": " + heartbeatData + " (offset: " + offset + ")");
            } else {
                // 未知特征，返回错误
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                Log.e(TAG, "收到未知特征读取请求: " + characteristic.getUuid());
            }
        }

        @Override
        public void onDescriptorReadRequest(android.bluetooth.BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);

            Log.d(TAG, "收到来自设备 " + device.getAddress() + " 的描述符读取请求: " + descriptor.getUuid());

            if (descriptor.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIG)) {
                // 返回客户端特征配置的当前值
                byte[] value = subscribedDevices.contains(device) ?
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE :
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;

                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                Log.d(TAG, "响应客户端特征配置读取请求到设备 " + device.getAddress() + ", 值: " + (subscribedDevices.contains(device) ? "ENABLE" : "DISABLE"));
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                Log.e(TAG, "收到未知描述符读取请求: " + descriptor.getUuid());
            }
        }

        @Override
        public void onDescriptorWriteRequest(android.bluetooth.BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);

            Log.d(TAG, "收到来自设备 " + device.getAddress() + " 的描述符写入请求: " + descriptor.getUuid());

            if (descriptor.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIG)) {
                // 处理客户端特征配置写入
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }

                // 检查是否启用通知
                if (value != null && value.length > 0) {
                    if (value[0] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0] ||
                            value[0] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[0]) {
                        // 客户端订阅了通知或指示
                        if (!subscribedDevices.contains(device)) {
                            subscribedDevices.add(device);
                            Log.i(TAG, "设备 " + device.getAddress() + " 订阅了心跳通知/指示.");
                        }
                    } else if (value[0] == BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE[0]) {
                        // 客户端取消订阅
                        subscribedDevices.remove(device);
                        Log.i(TAG, "设备 " + device.getAddress() + " 取消订阅了心跳通知/指示.");
                    } else {
                        Log.w(TAG, "收到来自设备 " + device.getAddress() + " 的未知通知/指示值.");
                    }
                }

                Log.d(TAG, "客户端特征配置已更新，当前订阅设备数: " + subscribedDevices.size());
            } else {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                }
                Log.e(TAG, "收到来自设备 " + device.getAddress() + " 的未知描述符写入请求: " + descriptor.getUuid());
            }
        }

        @Override
        public void onExecuteWrite(android.bluetooth.BluetoothDevice device, int requestId, boolean execute) {
            super.onExecuteWrite(device, requestId, execute);
            Log.d(TAG, "收到来自设备 " + device.getAddress() + " 的执行写入请求: " + execute);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override
        public void onCharacteristicWriteRequest(android.bluetooth.BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            // 如果你的心跳特征允许写入，需要在这里处理
            Log.d(TAG, "收到来自设备 " + device.getAddress() + " 的特征写入请求: " + characteristic.getUuid());
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
        }

        @Override
        public void onMtuChanged(android.bluetooth.BluetoothDevice device, int mtu) {
            super.onMtuChanged(device, mtu);
            Log.i(TAG, "设备 " + device.getAddress() + " MTU 已改变为: " + mtu);
        }
    };

    /**
     * 获取当前广播状态信息
     */
    public String getAdvertisingStatus() {
        StringBuilder status = new StringBuilder();
        status.append("广播状态: ").append(isAdvertising ? "运行中" : "已停止").append("\n");
        status.append("服务器状态: ").append(isServerRunning ? "运行中" : "已停止").append("\n");
        status.append("使用备用模式: ").append(isUsingFallback ? "是" : "否").append("\n");
        status.append("连接设备数: ").append(connectedDevices.size()).append("\n");
        status.append("订阅设备数: ").append(subscribedDevices.size()).append("\n");
        status.append("心跳计数: ").append(heartbeatCounter).append("\n");
        status.append("服务UUID: ").append(HEARTBEAT_SERVICE_UUID.toString()).append("\n");
        status.append("特征UUID: ").append(HEARTBEAT_CHARACTERISTIC_UUID.toString());
        return status.toString();
    }

    /**
     * 获取广播诊断信息和建议
     */
    public String getBroadcastDiagnostics() {
        StringBuilder diagnostics = new StringBuilder();
        diagnostics.append("=== BLE广播诊断信息 ===\n");
        
        // 基本状态
        diagnostics.append("广播状态: ").append(isAdvertising ? "✅ 运行中" : "❌ 已停止").append("\n");
        diagnostics.append("服务器状态: ").append(isServerRunning ? "✅ 运行中" : "❌ 已停止").append("\n");
        diagnostics.append("使用备用模式: ").append(isUsingFallback ? "是" : "否").append("\n");
        
        // 连接信息
        diagnostics.append("连接设备数: ").append(connectedDevices.size()).append("\n");
        diagnostics.append("订阅设备数: ").append(subscribedDevices.size()).append("\n");
        
        // UUID信息
        diagnostics.append("服务UUID: ").append(HEARTBEAT_SERVICE_UUID.toString()).append("\n");
        diagnostics.append("特征UUID: ").append(HEARTBEAT_CHARACTERISTIC_UUID.toString()).append("\n");
        
        // 建议
        diagnostics.append("\n=== 设备搜不到的可能原因 ===\n");
        if (!isAdvertising) {
            diagnostics.append("❌ 广播未启动，请检查权限和蓝牙状态\n");
        }
        if (connectedDevices.size() >= 7) {
            diagnostics.append("⚠️ 连接设备过多，可能影响新设备发现\n");
        }
        diagnostics.append("1. 设备距离过远或存在物理障碍\n");
        diagnostics.append("2. 扫描设备不支持当前广播模式\n");
        diagnostics.append("3. 蓝牙权限不足\n");
        diagnostics.append("4. 设备蓝牙版本不兼容\n");
        diagnostics.append("5. 系统资源不足\n");
        
        diagnostics.append("\n=== 解决建议 ===\n");
        diagnostics.append("1. 确保设备在有效范围内（通常10米内）\n");
        diagnostics.append("2. 重启蓝牙或重新启动广播\n");
        diagnostics.append("3. 检查扫描设备的蓝牙版本（建议4.0+）\n");
        diagnostics.append("4. 尝试不同的扫描应用\n");
        diagnostics.append("5. 检查设备是否支持BLE广播\n");
        
        return diagnostics.toString();
    }
}