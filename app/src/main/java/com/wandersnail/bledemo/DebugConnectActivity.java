package com.wandersnail.bledemo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import cn.wandersnail.ble.util.BluetoothDeviceLogger;

public class DebugConnectActivity extends AppCompatActivity {
    private EditText etMacAddress;
    private Button btnConnect;
    private Button btnDisconnect;
    private TextView tvStatus;
    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter bluetoothAdapter;

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            runOnUiThread(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        tvStatus.setText("已连接");
                        btnConnect.setEnabled(false);
                        btnDisconnect.setEnabled(true);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        tvStatus.setText("已断开连接");
                        btnConnect.setEnabled(true);
                        btnDisconnect.setEnabled(false);
                        gatt.close();
                    }
                } else {
                    tvStatus.setText("连接失败: " + status);
                    btnConnect.setEnabled(true);
                    btnDisconnect.setEnabled(false);
                    gatt.close();
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
        tvStatus = findViewById(R.id.tvStatus);

        // Set default MAC address
        etMacAddress.setText("64:09:AC:90:5E:37");

        // 初始状态下断开连接按钮禁用
        btnDisconnect.setEnabled(false);

        btnConnect.setOnClickListener(v -> connectDevice());
        btnDisconnect.setOnClickListener(v -> disconnectDevice());
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
        
        bluetoothGatt = device.connectGatt(null, false, gattCallback);
    }

    @SuppressLint("MissingPermission")
    private void disconnectDevice() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
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