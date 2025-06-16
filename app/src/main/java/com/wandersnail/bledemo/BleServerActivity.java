package com.wandersnail.bledemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class BleServerActivity extends AppCompatActivity {
    private static final String TAG = "BleServerActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;
    
    private BleServer bleServer;
    private BluetoothAdapter bluetoothAdapter;
    private TextView tvStatus;
    private TextView tvHeartbeatCount;
    private Button btnStartServer;
    private Button btnStopServer;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_server);
        
        // 设置工具栏
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("BLE服务器");
        }
        
        // 初始化视图
        initViews();
        
        // 初始化蓝牙
        initBluetooth();
        
        // 初始化BLE服务器
        bleServer = new BleServer(this);
        
        // 检查权限
        if (checkBluetoothPermissions()) {
            checkBluetoothEnabled();
        } else {
            requestBluetoothPermissions();
        }
    }
    
    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvHeartbeatCount = findViewById(R.id.tvHeartbeatCount);
        btnStartServer = findViewById(R.id.btnStartServer);
        btnStopServer = findViewById(R.id.btnStopServer);
        
        btnStartServer.setOnClickListener(v -> startServer());
        btnStopServer.setOnClickListener(v -> stopServer());
        
        // 初始状态
        btnStopServer.setEnabled(false);
        updateStatus("准备就绪");
    }
    
    private void initBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
        
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_LONG).show();
            finish();
        }
    }
    
    private void startServer() {
        if (bleServer.startServer()) {
            btnStartServer.setEnabled(false);
            btnStopServer.setEnabled(true);
            updateStatus("服务器已启动，等待客户端连接...");
            
            // 开始定时更新心跳计数
            startHeartbeatCounterUpdate();
        } else {
            updateStatus("服务器启动失败");
            Toast.makeText(this, "服务器启动失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void stopServer() {
        bleServer.stopServer();
        btnStartServer.setEnabled(true);
        btnStopServer.setEnabled(false);
        updateStatus("服务器已停止");
        
        // 停止心跳计数更新
        stopHeartbeatCounterUpdate();
    }
    
    private void updateStatus(String status) {
        tvStatus.setText("状态: " + status);
    }
    
    private void updateHeartbeatCount() {
        tvHeartbeatCount.setText("心跳计数: " + bleServer.getHeartbeatCounter());
    }
    
    private void startHeartbeatCounterUpdate() {
        // 每秒更新一次心跳计数
        new Thread(() -> {
            while (bleServer.isServerRunning()) {
                runOnUiThread(this::updateHeartbeatCount);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }
    
    private void stopHeartbeatCounterUpdate() {
        updateHeartbeatCount();
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleServer != null) {
            bleServer.stopServer();
        }
    }
    
    // 权限相关方法
    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH_PERMISSIONS
            );
        }
    }
    
    private void checkBluetoothEnabled() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            updateStatus("蓝牙已启用，可以启动服务器");
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                updateStatus("蓝牙已启用，可以启动服务器");
            } else {
                Toast.makeText(this, "需要启用蓝牙才能使用BLE服务器", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
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
                checkBluetoothEnabled();
            } else {
                Toast.makeText(this, "需要蓝牙权限才能使用BLE服务器", Toast.LENGTH_SHORT).show();
            }
        }
    }
} 