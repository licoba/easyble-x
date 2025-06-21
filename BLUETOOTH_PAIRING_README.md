# 蓝牙配对监听器使用说明

## 概述

本项目提供了两个蓝牙配对监听器类，用于监听系统蓝牙配对请求并自动同意配对：

1. `BluetoothPairingListener` - 基础版本，提供简单的自动配对功能
2. `AdvancedBluetoothPairingListener` - 高级版本，提供更多配置选项和回调

## 功能特性

### 基础功能
- 监听系统蓝牙配对请求
- 自动同意配对请求
- 处理不同的配对方式（PIN码、Passkey等）
- 监听设备连接/断开状态
- 监听绑定状态变化

### 高级功能
- 设备白名单/黑名单
- 可配置的自动配对策略
- 详细的配对回调
- 支持自定义PIN码
- 配对状态监控

## 使用方法

### 1. 基础使用

```java
// 创建监听器
BluetoothPairingListener listener = new BluetoothPairingListener(this);

// 开始监听
listener.startListening();

// 停止监听
listener.stopListening();
```

### 2. 高级使用

```java
// 创建高级监听器
AdvancedBluetoothPairingListener listener = new AdvancedBluetoothPairingListener(this);

// 设置配对回调
listener.setPairingCallback(new AdvancedBluetoothPairingListener.PairingCallback() {
    @Override
    public void onPairingRequest(BluetoothDevice device, int pairingVariant) {
        // 收到配对请求
    }

    @Override
    public void onPairingAccepted(BluetoothDevice device) {
        // 配对请求被接受
    }

    @Override
    public void onPairingRejected(BluetoothDevice device) {
        // 配对请求被拒绝
    }

    @Override
    public void onPairingCompleted(BluetoothDevice device, boolean success) {
        // 配对完成
    }

    @Override
    public void onPairingFailed(BluetoothDevice device, String reason) {
        // 配对失败
    }
});

// 配置监听器
listener.setAutoAcceptPairing(true);        // 自动接受配对
listener.setAutoAcceptAllDevices(true);     // 接受所有设备
listener.setDefaultPin("0000");             // 设置默认PIN码

// 添加允许的设备（可选）
listener.addAllowedDevice("AA:BB:CC:DD:EE:FF");

// 添加阻止的设备（可选）
listener.addBlockedDevice("11:22:33:44:55:66");

// 开始监听
listener.startListening();
```

## 权限要求

由于这是系统级应用功能，需要在 `AndroidManifest.xml` 中声明以下权限：

```xml
<!-- 系统级蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH_PRIVILEGED" />
<uses-permission android:name="android.permission.MODIFY_PHONE_STATE" />

<!-- 基础蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

## 配对方式说明

### 支持的配对变体
- `PAIRING_VARIANT_PIN` - PIN码配对
- `PAIRING_VARIANT_PASSKEY_CONFIRMATION` - 需要用户确认的配对

**注意**: 其他配对变体常量（如 `PAIRING_VARIANT_CONSENT`、`PAIRING_VARIANT_DISPLAY_PASSKEY`、`PAIRING_VARIANT_DISPLAY_PIN`）在Android SDK中不存在，代码中已移除对这些常量的引用。

### 自动处理策略
1. **PIN码配对**: 自动设置默认PIN码（通常是"0000"）
2. **确认配对**: 自动设置配对确认为true
3. **显示配对**: 记录配对信息到日志

## 注意事项

1. **系统权限**: 此功能需要系统级权限，应用必须使用 `android:sharedUserId="android.uid.system"`
2. **安全性**: 自动配对可能带来安全风险，建议在生产环境中谨慎使用
3. **设备兼容性**: 不同设备的配对行为可能有所不同
4. **Android版本**: 不同Android版本的蓝牙API可能有差异

## 日志输出

监听器会输出详细的日志信息，包括：
- 配对请求的接收
- 配对状态的变化
- 设备连接/断开
- 错误信息

可以通过 `adb logcat` 查看日志：
```bash
adb logcat | grep -E "(BluetoothPairing|AdvancedBluetoothPairing|ScanActivity)"
```

## 示例场景

### 场景1: 自动接受所有配对请求
```java
AdvancedBluetoothPairingListener listener = new AdvancedBluetoothPairingListener(this);
listener.setAutoAcceptPairing(true);
listener.setAutoAcceptAllDevices(true);
listener.startListening();
```

### 场景2: 只接受特定设备的配对
```java
AdvancedBluetoothPairingListener listener = new AdvancedBluetoothPairingListener(this);
listener.setAutoAcceptPairing(true);
listener.setAutoAcceptAllDevices(false);
listener.addAllowedDevice("AA:BB:CC:DD:EE:FF");
listener.addAllowedDevice("11:22:33:44:55:66");
listener.startListening();
```

### 场景3: 阻止特定设备的配对
```java
AdvancedBluetoothPairingListener listener = new AdvancedBluetoothPairingListener(this);
listener.setAutoAcceptPairing(true);
listener.setAutoAcceptAllDevices(true);
listener.addBlockedDevice("FF:EE:DD:CC:BB:AA");
listener.startListening();
```

## 故障排除

### 常见问题

1. **权限不足**: 确保应用有系统级权限
2. **配对失败**: 检查设备是否支持相应的配对方式
3. **监听器未启动**: 确保在Activity的onCreate中调用startListening()
4. **内存泄漏**: 确保在Activity的onDestroy中调用stopListening()

### 调试建议

1. 启用详细日志输出
2. 检查设备配对变体类型
3. 验证PIN码设置是否正确
4. 确认广播接收器注册成功 