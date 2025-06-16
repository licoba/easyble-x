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
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")

public class BleServer {
    private static final String TAG = "BleServer";
    
    // 自定义服务UUID
    private static final UUID HEARTBEAT_SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789ABC");
    // 心跳特征UUID
    private static final UUID HEARTBEAT_CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-4321-4321-CBA987654321");
    // 客户端特征配置描述符UUID
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    
    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothGattServer gattServer;
    private BluetoothGattService heartbeatService;
    private BluetoothGattCharacteristic heartbeatCharacteristic;
    private Handler handler;
    private boolean isServerRunning = false;
    private int heartbeatCounter = 0;
    private List<android.bluetooth.BluetoothDevice> connectedDevices = new ArrayList<>();
    
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
            Log.w(TAG, "服务器已在运行");
            return true;
        }
        
        if (bluetoothManager == null) {
            Log.e(TAG, "BluetoothManager不可用");
            return false;
        }
        
        // 创建GATT服务器
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            Log.e(TAG, "无法创建GATT服务器");
            return false;
        }
        
        // 创建心跳服务
        heartbeatService = new BluetoothGattService(
                HEARTBEAT_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );
        
        // 创建心跳特征
        heartbeatCharacteristic = new BluetoothGattCharacteristic(
                HEARTBEAT_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ | 
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        
        // 添加客户端特征配置描述符
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
            Log.i(TAG, "BLE服务器启动成功");
            // 开始发送心跳
            startHeartbeat();
            return true;
        } else {
            Log.e(TAG, "添加服务失败");
            gattServer.close();
            gattServer = null;
            return false;
        }
    }
    
    /**
     * 停止BLE服务器
     */
    @SuppressLint("MissingPermission")
    public void stopServer() {
        if (!isServerRunning) {
            return;
        }
        
        // 停止心跳
        stopHeartbeat();
        
        // 清理连接的设备列表
        connectedDevices.clear();
        
        // 关闭GATT服务器
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        
        isServerRunning = false;
        Log.i(TAG, "BLE服务器已停止");
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
    }
    
    /**
     * 停止发送心跳
     */
    private void stopHeartbeat() {
        handler.removeCallbacksAndMessages(null);
    }
    
    /**
     * 发送心跳数据
     */
    private void sendHeartbeat() {
        if (gattServer == null || heartbeatCharacteristic == null) {
            return;
        }
        
        // 创建心跳数据
        heartbeatCounter++;
        long timestamp = System.currentTimeMillis();
        
        // 创建更结构化的心跳数据
        String heartbeatData = String.format("{\"type\":\"heartbeat\",\"count\":%d,\"timestamp\":%d,\"device\":\"Android\"}", 
                heartbeatCounter, timestamp);
        
        byte[] data = heartbeatData.getBytes();
        heartbeatCharacteristic.setValue(data);
        
        // 向所有已连接的客户端发送通知
        boolean allSuccess = true;
        if (connectedDevices.isEmpty()) {
            Log.d(TAG, "没有连接的客户端，跳过心跳发送");
            return;
        }
        
        for (android.bluetooth.BluetoothDevice device : connectedDevices) {
            boolean success = gattServer.notifyCharacteristicChanged(
                    device,
                    heartbeatCharacteristic,
                    false // 不需要确认
            );
            
            if (success) {
                Log.d(TAG, "心跳发送成功到设备 " + device.getAddress() + ": " + heartbeatData);
            } else {
                Log.e(TAG, "心跳发送失败到设备 " + device.getAddress());
                allSuccess = false;
            }
        }
        
        if (allSuccess) {
            Log.d(TAG, "心跳发送成功到所有设备: " + heartbeatData);
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
     * GATT服务器回调
     */
    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(android.bluetooth.BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "客户端已连接: " + device.getAddress());
                    connectedDevices.add(device);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "客户端已断开: " + device.getAddress());
                    connectedDevices.remove(device);
                }
            } else {
                Log.e(TAG, "连接状态改变失败: " + status);
            }
        }
        
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            super.onServiceAdded(status, service);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "服务添加成功: " + service.getUuid());
            } else {
                Log.e(TAG, "服务添加失败: " + status);
            }
        }
        
        @Override
        public void onCharacteristicReadRequest(android.bluetooth.BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            
            Log.d(TAG, "收到特征读取请求: " + characteristic.getUuid());
            
            if (characteristic.getUuid().equals(HEARTBEAT_CHARACTERISTIC_UUID)) {
                // 返回当前心跳数据
                String heartbeatData = String.format("Heartbeat #%d - %s", 
                        heartbeatCounter, 
                        System.currentTimeMillis());
                byte[] data = heartbeatData.getBytes();
                
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data);
                Log.d(TAG, "响应心跳读取请求: " + heartbeatData);
            } else {
                // 未知特征，返回错误
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
            }
        }
        
        @Override
        public void onDescriptorReadRequest(android.bluetooth.BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            
            Log.d(TAG, "收到描述符读取请求: " + descriptor.getUuid());
            
            if (descriptor.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIG)) {
                // 返回客户端特征配置
                byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
            }
        }
        
        @Override
        public void onDescriptorWriteRequest(android.bluetooth.BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            
            Log.d(TAG, "收到描述符写入请求: " + descriptor.getUuid());
            
            if (descriptor.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIG)) {
                // 处理客户端特征配置写入
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }
                Log.d(TAG, "客户端特征配置已更新");
            } else {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                }
            }
        }
        
        @Override
        public void onExecuteWrite(android.bluetooth.BluetoothDevice device, int requestId, boolean execute) {
            super.onExecuteWrite(device, requestId, execute);
            Log.d(TAG, "收到执行写入请求: " + execute);
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    };
} 