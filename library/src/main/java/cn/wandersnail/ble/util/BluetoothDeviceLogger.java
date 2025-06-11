package cn.wandersnail.ble.util;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.util.Log;

/**
 * 蓝牙设备信息日志工具类
 */
public class BluetoothDeviceLogger {
    private static final String TAG = "BluetoothDeviceLogger";
    private static BluetoothDeviceLogger instance;

    private BluetoothDeviceLogger() {
        // 私有构造函数
    }

    public static BluetoothDeviceLogger getInstance() {
        if (instance == null) {
            synchronized (BluetoothDeviceLogger.class) {
                if (instance == null) {
                    instance = new BluetoothDeviceLogger();
                }
            }
        }
        return instance;
    }

    /**
     * 打印设备详细信息
     */
    @SuppressLint("MissingPermission")
    public void logDeviceInfo(BluetoothDevice device) {
        if (device == null) {
            Log.d(TAG, "设备为空");
            return;
        }

        Log.d(TAG, "设备信息:");
        Log.d(TAG, "MAC地址: " + device.getAddress());
        Log.d(TAG, "设备名称: " + device.getName());
        Log.d(TAG, "设备类型: " + getDeviceTypeString(device.getType()));
        Log.d(TAG, "绑定状态: " + getBondStateString(device.getBondState()));
        Log.d(TAG, "设备类别: " + getDeviceClassString(device.getBluetoothClass().getDeviceClass()));
        Log.d(TAG, "主要类别: " + getMajorDeviceClassString(device.getBluetoothClass().getMajorDeviceClass()));
    }

    private String getDeviceTypeString(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                return "传统蓝牙设备 (CLASSIC)";
            case BluetoothDevice.DEVICE_TYPE_LE:
                return "低功耗蓝牙设备 (LE)";
            case BluetoothDevice.DEVICE_TYPE_DUAL:
                return "双模蓝牙设备 (DUAL) - 同时支持传统蓝牙和低功耗蓝牙";
            case BluetoothDevice.DEVICE_TYPE_UNKNOWN:
            default:
                return "未知类型设备 (UNKNOWN)";
        }
    }

    private String getBondStateString(int state) {
        switch (state) {
            case BluetoothDevice.BOND_BONDED:
                return "已配对 (BONDED)";
            case BluetoothDevice.BOND_BONDING:
                return "正在配对 (BONDING)";
            case BluetoothDevice.BOND_NONE:
            default:
                return "未配对 (NONE)";
        }
    }

    private String getDeviceClassString(int deviceClass) {
        switch (deviceClass) {
            case BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER:
                return "摄像机";
            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                return "车载音频";
            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                return "免提设备";
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return "耳机";
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                return "高保真音频设备";
            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
                return "扬声器";
            case BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE:
                return "麦克风";
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                return "便携式音频设备";
            case BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX:
                return "机顶盒";
            case BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED:
                return "未分类的音视频设备";
            case BluetoothClass.Device.AUDIO_VIDEO_VCR:
                return "录像机";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA:
                return "摄像机";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING:
                return "视频会议设备";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
                return "视频显示和扬声器";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY:
                return "视频游戏玩具";
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR:
                return "视频监视器";
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                return "可穿戴耳机";
            case BluetoothClass.Device.COMPUTER_DESKTOP:
                return "台式电脑";
            case BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA:
                return "手持电脑/PDA";
            case BluetoothClass.Device.COMPUTER_LAPTOP:
                return "笔记本电脑";
            case BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA:
                return "掌上电脑/PDA";
            case BluetoothClass.Device.COMPUTER_SERVER:
                return "服务器";
            case BluetoothClass.Device.COMPUTER_UNCATEGORIZED:
                return "未分类的计算机设备";
            case BluetoothClass.Device.COMPUTER_WEARABLE:
                return "可穿戴计算机";
            case BluetoothClass.Device.HEALTH_BLOOD_PRESSURE:
                return "血压计";
            case BluetoothClass.Device.HEALTH_DATA_DISPLAY:
                return "健康数据显示器";
            case BluetoothClass.Device.HEALTH_GLUCOSE:
                return "血糖仪";
            case BluetoothClass.Device.HEALTH_PULSE_OXIMETER:
                return "脉搏血氧仪";
            case BluetoothClass.Device.HEALTH_PULSE_RATE:
                return "心率计";
            case BluetoothClass.Device.HEALTH_THERMOMETER:
                return "体温计";
            case BluetoothClass.Device.HEALTH_UNCATEGORIZED:
                return "未分类的健康设备";
            case BluetoothClass.Device.HEALTH_WEIGHING:
                return "体重计";
            case BluetoothClass.Device.PHONE_CELLULAR:
                return "手机";
            case BluetoothClass.Device.PHONE_CORDLESS:
                return "无绳电话";
            case BluetoothClass.Device.PHONE_ISDN:
                return "ISDN电话";
            case BluetoothClass.Device.PHONE_MODEM_OR_GATEWAY:
                return "调制解调器或网关";
            case BluetoothClass.Device.PHONE_SMART:
                return "智能手机";
            case BluetoothClass.Device.PHONE_UNCATEGORIZED:
                return "未分类的电话设备";
            case BluetoothClass.Device.TOY_CONTROLLER:
                return "玩具控制器";
            case BluetoothClass.Device.TOY_DOLL_ACTION_FIGURE:
                return "玩具娃娃/动作人偶";
            case BluetoothClass.Device.TOY_GAME:
                return "游戏玩具";
            case BluetoothClass.Device.TOY_ROBOT:
                return "机器人玩具";
            case BluetoothClass.Device.TOY_UNCATEGORIZED:
                return "未分类的玩具";
            case BluetoothClass.Device.TOY_VEHICLE:
                return "玩具车辆";
            case BluetoothClass.Device.WEARABLE_GLASSES:
                return "智能眼镜";
            case BluetoothClass.Device.WEARABLE_HELMET:
                return "智能头盔";
            case BluetoothClass.Device.WEARABLE_JACKET:
                return "智能夹克";
            case BluetoothClass.Device.WEARABLE_PAGER:
                return "寻呼机";
            case BluetoothClass.Device.WEARABLE_UNCATEGORIZED:
                return "未分类的可穿戴设备";
            case BluetoothClass.Device.WEARABLE_WRIST_WATCH:
                return "智能手表";
            default:
                return "未知设备类别 (" + deviceClass + ")";
        }
    }

    private String getMajorDeviceClassString(int majorClass) {
        switch (majorClass) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                return "音视频设备";
            case BluetoothClass.Device.Major.COMPUTER:
                return "计算机设备";
            case BluetoothClass.Device.Major.HEALTH:
                return "健康设备";
            case BluetoothClass.Device.Major.IMAGING:
                return "图像设备";
            case BluetoothClass.Device.Major.MISC:
                return "其他设备";
            case BluetoothClass.Device.Major.NETWORKING:
                return "网络设备";
            case BluetoothClass.Device.Major.PERIPHERAL:
                return "外设设备";
            case BluetoothClass.Device.Major.PHONE:
                return "电话设备";
            case BluetoothClass.Device.Major.TOY:
                return "玩具设备";
            case BluetoothClass.Device.Major.UNCATEGORIZED:
                return "未分类设备";
            case BluetoothClass.Device.Major.WEARABLE:
                return "可穿戴设备";
            default:
                return "未知主要类别 (" + majorClass + ")";
        }
    }
} 