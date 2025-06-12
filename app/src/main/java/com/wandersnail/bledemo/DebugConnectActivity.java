package com.wandersnail.bledemo;

import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import cn.wandersnail.ble.util.BluetoothDeviceLogger;

public class DebugConnectActivity extends AppCompatActivity {
    private EditText etMacAddress;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnManualDisconnect;
    private TextView tvStatus;
    private RadioGroup rgTransport;
    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter bluetoothAdapter;

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            runOnUiThread(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        String deviceType = "未知";
                        try {
                            int type = gatt.getDevice().getType();
                            switch (type) {
                                case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                                    deviceType = "BR/EDR";
                                    break;
                                case BluetoothDevice.DEVICE_TYPE_LE:
                                    deviceType = "LE";
                                    break;
                                case BluetoothDevice.DEVICE_TYPE_DUAL:
                                    deviceType = "双模";
                                    break;
                                default:
                                    deviceType = "未知类型";
                                    break;
                            }
                        } catch (Exception e) {
                            Log.e("DebugConnect", "获取设备类型失败", e);
                        }
                        
                        tvStatus.setText("已连接 (设备类型: " + deviceType + ")");
                        btnConnect.setEnabled(false);
                        btnDisconnect.setEnabled(true);
                        // 连接成功后再次打印设备信息
                        BluetoothDevice device = gatt.getDevice();
                        BluetoothDeviceLogger.getInstance().logDeviceInfo(device);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        tvStatus.setText("已断开连接");
                        btnConnect.setEnabled(true);
                        btnDisconnect.setEnabled(false);
//                        gatt.close();
                        manualDisconnectGatt();
                    }
                } else {
                    tvStatus.setText("连接失败: " + status);
                    btnConnect.setEnabled(true);
                    btnDisconnect.setEnabled(false);
                    manualDisconnectGatt();

//                    gatt.close();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug_connect);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Debug Connect");
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        etMacAddress = findViewById(R.id.etMacAddress);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnManualDisconnect = findViewById(R.id.btnManualDisconnect);
        tvStatus = findViewById(R.id.tvStatus);
        rgTransport = findViewById(R.id.rgTransport);

        // Set default MAC address
        etMacAddress.setText("64:09:AC:90:5E:37");

        // 初始状态下断开连接按钮禁用
        btnDisconnect.setEnabled(false);

        btnConnect.setOnClickListener(v -> connectDevice());
        btnDisconnect.setOnClickListener(v -> disconnectDevice());
        btnManualDisconnect.setOnClickListener(v -> manualDisconnectGatt());
    }

    @SuppressLint("MissingPermission")
    private void connectDevice() {
        String macAddress = etMacAddress.getText().toString().trim();
        if (TextUtils.isEmpty(macAddress)) {
            Toast.makeText(this, "Please enter MAC address", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!macAddress.matches("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")) {
            Toast.makeText(this, "Invalid MAC address format", Toast.LENGTH_SHORT).show();
            return;
        }

        btnConnect.setEnabled(false);
        tvStatus.setText("正在连接...");

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
        BluetoothDeviceLogger.getInstance().logDeviceInfo(device);
        
        int transport = TRANSPORT_LE;
        int checkedId = rgTransport.getCheckedRadioButtonId();
        if(checkedId == R.id.rbTransportAuto){
            transport = TRANSPORT_AUTO;
        }else if (checkedId == R.id.rbTransportBredr) {
            transport = TRANSPORT_BREDR;
        } else if (checkedId == R.id.rbTransportLe) {
            transport = TRANSPORT_LE;
        }
        
        bluetoothGatt = device.connectGatt(null, false, gattCallback, transport);
    }

    @SuppressLint("MissingPermission")
    private void disconnectDevice() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    @SuppressLint("MissingPermission")
    private void manualDisconnectGatt() {
        if (bluetoothGatt != null) {
            try {
                // 使用反射调用refresh方法
                try {
                    java.lang.reflect.Method refresh = bluetoothGatt.getClass().getMethod("refresh");
                    boolean success = (boolean) refresh.invoke(bluetoothGatt);
                    Log.d("DebugConnect", "Refreshing device cache: " + (success ? "success" : "failed"));
                } catch (Exception e) {
                    Log.e("DebugConnect", "gatt.refresh() method not found", e);
                }

                bluetoothGatt.disconnect();
                bluetoothGatt.close();
                bluetoothGatt = null;
                tvStatus.setText("已手动断开GATT连接");
                btnConnect.setEnabled(true);
                btnDisconnect.setEnabled(false);
                btnManualDisconnect.setEnabled(true);
            } catch (Exception e) {
                Toast.makeText(this, "手动断开GATT连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
        }
    }
} 