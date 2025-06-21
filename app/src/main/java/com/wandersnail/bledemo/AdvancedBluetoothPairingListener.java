package com.wandersnail.bledemo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * 高级蓝牙配对监听器
 * 支持更多配对场景和配置选项
 * 适用于系统级应用
 */
@SuppressLint("MissingPermission")
public class AdvancedBluetoothPairingListener {
    private static final String TAG = "AdvancedBluetoothPairing";
    
    private final Context context;
    private final BroadcastReceiver pairingReceiver;
    private boolean isRegistered = false;
    
    // 配置选项
    private boolean autoAcceptPairing = true;
    private boolean autoAcceptAllDevices = true;
    private Set<String> allowedDeviceAddresses = new HashSet<>();
    private Set<String> blockedDeviceAddresses = new HashSet<>();
    private PairingCallback pairingCallback;
    
    public interface PairingCallback {
        void onPairingRequest(BluetoothDevice device, int pairingVariant);
        void onPairingAccepted(BluetoothDevice device);
        void onPairingRejected(BluetoothDevice device);
        void onPairingCompleted(BluetoothDevice device, boolean success);
        void onPairingFailed(BluetoothDevice device, String reason);
    }
    
    public AdvancedBluetoothPairingListener(Context context) {
        this.context = context;
        this.pairingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                Log.d(TAG, "Received broadcast: " + action);
                
                switch (action) {
                    case BluetoothDevice.ACTION_PAIRING_REQUEST:
                        handlePairingRequest(intent);
                        break;
                    case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                        handleBondStateChanged(intent);
                        break;
                    case BluetoothDevice.ACTION_ACL_CONNECTED:
                        handleDeviceConnected(intent);
                        break;
                    case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                        handleDeviceDisconnected(intent);
                        break;
                }
            }
        };
    }
    
    /**
     * 设置配对回调
     */
    public void setPairingCallback(PairingCallback callback) {
        this.pairingCallback = callback;
    }
    
    /**
     * 设置是否自动接受配对
     */
    public void setAutoAcceptPairing(boolean autoAccept) {
        this.autoAcceptPairing = autoAccept;
    }
    
    /**
     * 设置是否接受所有设备
     */
    public void setAutoAcceptAllDevices(boolean acceptAll) {
        this.autoAcceptAllDevices = acceptAll;
    }
    
    /**
     * 添加允许配对的设备地址
     */
    public void addAllowedDevice(String address) {
        allowedDeviceAddresses.add(address.toUpperCase());
    }
    
    /**
     * 添加阻止配对的设备地址
     */
    public void addBlockedDevice(String address) {
        blockedDeviceAddresses.add(address.toUpperCase());
    }
    
    /**
     * 开始监听蓝牙配对请求
     */
    public void startListening() {
        if (isRegistered) {
            Log.w(TAG, "Already listening for pairing requests");
            return;
        }
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        
        try {
            context.registerReceiver(pairingReceiver, filter);
            isRegistered = true;
            Log.i(TAG, "Started listening for Bluetooth pairing requests");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register pairing receiver", e);
        }
    }
    
    /**
     * 停止监听蓝牙配对请求
     */
    public void stopListening() {
        if (!isRegistered) {
            return;
        }
        
        try {
            context.unregisterReceiver(pairingReceiver);
            isRegistered = false;
            Log.i(TAG, "Stopped listening for Bluetooth pairing requests");
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister pairing receiver", e);
        }
    }
    
    /**
     * 处理配对请求
     */
    private void handlePairingRequest(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            Log.w(TAG, "No device found in pairing request");
            return;
        }
        
        String deviceAddress = device.getAddress().toUpperCase();
        String deviceName = device.getName();
        int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
        
        Log.i(TAG, "Received pairing request from: " + deviceAddress + 
              " (" + deviceName + ") variant: " + pairingVariant);
        
        // 通知回调
        if (pairingCallback != null) {
            pairingCallback.onPairingRequest(device, pairingVariant);
        }
        
        // 检查是否应该接受配对
        if (!shouldAcceptPairing(deviceAddress)) {
            Log.i(TAG, "Rejecting pairing request from: " + deviceAddress);
            if (pairingCallback != null) {
                pairingCallback.onPairingRejected(device);
            }
            return;
        }
        
        // 自动同意配对请求
        if (autoAcceptPairing) {
            try {
                acceptPairingRequest(device, pairingVariant);
                Log.i(TAG, "Automatically accepted pairing request from: " + deviceAddress);
                if (pairingCallback != null) {
                    pairingCallback.onPairingAccepted(device);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error accepting pairing request", e);
                if (pairingCallback != null) {
                    pairingCallback.onPairingFailed(device, e.getMessage());
                }
            }
        }
    }
    
    /**
     * 检查是否应该接受配对
     */
    private boolean shouldAcceptPairing(String deviceAddress) {
        // 检查是否在阻止列表中
        if (blockedDeviceAddresses.contains(deviceAddress)) {
            Log.i(TAG, "Device " + deviceAddress + " is in blocked list");
            return false;
        }
        
        // 如果设置了只接受特定设备，检查是否在允许列表中
        if (!autoAcceptAllDevices && !allowedDeviceAddresses.isEmpty()) {
            if (!allowedDeviceAddresses.contains(deviceAddress)) {
                Log.i(TAG, "Device " + deviceAddress + " is not in allowed list");
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 接受配对请求
     */
    private void acceptPairingRequest(BluetoothDevice device, int pairingVariant) {
        try {
            // 设置配对方式为自动确认
            device.setPairingConfirmation(true);
            Log.d(TAG, "Set pairing confirmation to true for device: " + device.getAddress());
            
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when accepting pairing request", e);
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "Error accepting pairing request", e);
            throw e;
        }
    }
    
    /**
     * 处理绑定状态变化
     */
    @SuppressLint("MissingPermission")
    private void handleBondStateChanged(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) return;
        
        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 
                                         BluetoothDevice.ERROR);
        int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, 
                                                 BluetoothDevice.ERROR);
        
        String deviceInfo = device.getAddress() + " (" + device.getName() + ")";
        
        switch (bondState) {
            case BluetoothDevice.BOND_BONDED:
                Log.i(TAG, "Device bonded: " + deviceInfo);
                if (pairingCallback != null) {
                    pairingCallback.onPairingCompleted(device, true);
                }
                break;
            case BluetoothDevice.BOND_BONDING:
                Log.i(TAG, "Device bonding: " + deviceInfo);
                break;
            case BluetoothDevice.BOND_NONE:
                if (previousBondState == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "Device unpaired: " + deviceInfo);
                } else if (previousBondState == BluetoothDevice.BOND_BONDING) {
                    Log.w(TAG, "Device pairing failed: " + deviceInfo);
                    if (pairingCallback != null) {
                        pairingCallback.onPairingCompleted(device, false);
                    }
                }
                break;
        }
    }
    
    /**
     * 处理设备连接
     */
    @SuppressLint("MissingPermission")
    private void handleDeviceConnected(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device != null) {
            Log.i(TAG, "Device connected: " + device.getAddress() + " (" + device.getName() + ")");
        }
    }
    
    /**
     * 处理设备断开连接
     */
    private void handleDeviceDisconnected(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device != null) {
            Log.i(TAG, "Device disconnected: " + device.getAddress() + " (" + device.getName() + ")");
        }
    }
    
    /**
     * 检查是否正在监听
     */
    public boolean isListening() {
        return isRegistered;
    }
    
    /**
     * 清除所有配置
     */
    public void clearConfiguration() {
        allowedDeviceAddresses.clear();
        blockedDeviceAddresses.clear();
        autoAcceptPairing = true;
        autoAcceptAllDevices = true;
    }
} 