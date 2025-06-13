package com.wandersnail.bledemo;

import static android.bluetooth.BluetoothDevice.TRANSPORT_AUTO;
import static android.bluetooth.BluetoothDevice.TRANSPORT_BREDR;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.wandersnail.ble.util.BluetoothDeviceLogger;

public class DebugConnectActivity extends AppCompatActivity {
    private EditText etMacAddress;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnManualDisconnect;
    private Button btnGetDeviceInfo;
    private TextView tvStatus;
    private TextView tvServices;
    private RadioGroup rgTransport;
    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter bluetoothAdapter;
    @SuppressLint("MissingPermission")
    private BluetoothProfile.ServiceListener profileListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            StringBuilder sb = new StringBuilder();
            sb.append("已连接的经典蓝牙设备：\n\n");
            
            if (profile == BluetoothProfile.A2DP) {
                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                List<BluetoothDevice> connectedDevices = a2dp.getConnectedDevices();
                if (!connectedDevices.isEmpty()) {
                    sb.append("音频设备 (A2DP):\n");
                    for (BluetoothDevice device : connectedDevices) {
                        appendDeviceInfo(sb, device, "A2DP", a2dp.getConnectionState(device) == BluetoothA2dp.STATE_CONNECTED);
                    }
                }
            } else if (profile == BluetoothProfile.HEADSET) {
                BluetoothHeadset headset = (BluetoothHeadset) proxy;
                List<BluetoothDevice> connectedDevices = headset.getConnectedDevices();
                if (!connectedDevices.isEmpty()) {
                    sb.append("\n双向音频设备\n");
                    for (BluetoothDevice device : connectedDevices) {
                        appendDeviceInfo(sb, device, "Headset", headset.getConnectionState(device) == BluetoothHeadset.STATE_CONNECTED);
                    }
                }
            }
            
            if (sb.toString().equals("已连接的经典蓝牙设备：\n\n")) {
                sb.append("没有已连接的经典蓝牙设备");
            }
            tvServices.setText(sb.toString());
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP || profile == BluetoothProfile.HEADSET) {
                tvServices.setText("获取连接状态失败");
            }
        }
    };

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
                        // 连接成功后自动发现服务
                        gatt.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        tvStatus.setText("已断开连接");
                        btnConnect.setEnabled(true);
                        btnDisconnect.setEnabled(false);
                        tvServices.setText("");
                        manualDisconnectGatt();
                    }
                } else {
                    tvStatus.setText("连接失败: " + status);
                    btnConnect.setEnabled(true);
                    btnDisconnect.setEnabled(false);
                    tvServices.setText("");
                    manualDisconnectGatt();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            runOnUiThread(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    StringBuilder sb = new StringBuilder();
                    List<BluetoothGattService> services = gatt.getServices();
                    sb.append("发现服务数量: ").append(services.size()).append("\n\n");

                    for (BluetoothGattService service : services) {
                        sb.append("服务: ").append(service.getUuid()).append("\n");
                        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                        sb.append("特征数量: ").append(characteristics.size()).append("\n");

                        for (BluetoothGattCharacteristic characteristic : characteristics) {
                            sb.append("  特征: ").append(characteristic.getUuid()).append("\n");
                            sb.append("  属性: ");
                            int properties = characteristic.getProperties();
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                                sb.append("READ ");
                            }
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                                sb.append("WRITE ");
                            }
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                sb.append("WRITE_NO_RESPONSE ");
                            }
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                                sb.append("NOTIFY ");
                            }
                            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                                sb.append("INDICATE ");
                            }
                            sb.append("\n");

                            // 获取描述符
                            List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
                            if (!descriptors.isEmpty()) {
                                sb.append("  描述符:\n");
                                for (BluetoothGattDescriptor descriptor : descriptors) {
                                    sb.append("    ").append(descriptor.getUuid()).append("\n");
                                }
                            }
                            sb.append("\n");
                        }
                        sb.append("\n");
                    }
                    tvServices.setText(sb.toString());
                } else {
                    tvServices.setText("服务发现失败: " + status);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] value = characteristic.getValue();
                String hexValue = bytesToHex(value);
                Log.d("DebugConnect", "读取特征值成功: " + characteristic.getUuid() + " = " + hexValue);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("DebugConnect", "写入特征值成功: " + characteristic.getUuid());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            String hexValue = bytesToHex(value);
            Log.d("DebugConnect", "特征值变化: " + characteristic.getUuid() + " = " + hexValue);
        }
    };

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

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
        btnGetDeviceInfo = findViewById(R.id.btnGetDeviceInfo);
        tvStatus = findViewById(R.id.tvStatus);
        tvServices = findViewById(R.id.tvServices);
        rgTransport = findViewById(R.id.rgTransport);

        // Set default MAC address
        etMacAddress.setText("64:09:AC:90:5E:37");

        // 初始状态下断开连接按钮禁用
        btnDisconnect.setEnabled(false);

        btnConnect.setOnClickListener(v -> connectDevice());
        btnDisconnect.setOnClickListener(v -> disconnectDevice());
        btnManualDisconnect.setOnClickListener(v -> manualDisconnectGatt());
        btnGetDeviceInfo.setOnClickListener(v -> getDeviceInfo());
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
        tvServices.setText("");

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
        BluetoothDeviceLogger.getInstance().logDeviceInfo(device);

        int transport = TRANSPORT_LE;
        int checkedId = rgTransport.getCheckedRadioButtonId();
        if (checkedId == R.id.rbTransportAuto) {
            transport = TRANSPORT_AUTO;
        } else if (checkedId == R.id.rbTransportBredr) {
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

    @SuppressLint("MissingPermission")
    private void getDeviceInfo() {
        if (bluetoothAdapter != null) {
            // 获取A2DP和HFP配置文件来检查经典蓝牙连接状态
            bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
            bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET);
        } else {
            tvServices.setText("蓝牙适配器不可用");
        }
    }

    @SuppressLint("MissingPermission")
    private void appendDeviceInfo(StringBuilder sb, BluetoothDevice device, String profile, boolean isConnected) {
        sb.append("设备名称: ").append(device.getName()).append("\n");
        sb.append("MAC地址: ").append(device.getAddress()).append("\n");
        sb.append("设备类型: ");
        switch (device.getType()) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                sb.append("经典蓝牙");
                break;
            case BluetoothDevice.DEVICE_TYPE_LE:
                sb.append("低功耗蓝牙");
                break;
            case BluetoothDevice.DEVICE_TYPE_DUAL:
                sb.append("双模蓝牙");
                break;
            default:
                sb.append("未知类型");
                break;
        }
        sb.append("\n");
        sb.append("配置文件: ").append(profile).append("\n");
        sb.append("连接状态: ").append(isConnected ? "已连接" : "未连接").append("\n");
        sb.append("----------------------------------------\n");
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