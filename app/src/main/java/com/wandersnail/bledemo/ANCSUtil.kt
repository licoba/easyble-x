package com.wandersnail.bledemo

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * ANCS (Apple Notification Center Service) 工具类
 * 负责处理 ANCS 相关的操作，包括通知解析、命令构建等
 */
class ANCSUtil {
    
    companion object {
        private const val TAG = "ANCSUtil"
        
        // ANCS 服务相关 UUID
        val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        
        // 标准客户端特征配置描述符 UUID
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        // ANCS 命令 ID
        const val COMMAND_GET_NOTIFICATION_ATTRIBUTES: Byte = 0x00
        const val COMMAND_GET_APP_ATTRIBUTES: Byte = 0x01
        const val COMMAND_PERFORM_NOTIFICATION_ACTION: Byte = 0x02
        
        // ANCS 通知属性 ID
        const val NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER: Byte = 0x00
        const val NOTIFICATION_ATTRIBUTE_TITLE: Byte = 0x01
        const val NOTIFICATION_ATTRIBUTE_SUBTITLE: Byte = 0x02
        const val NOTIFICATION_ATTRIBUTE_MESSAGE: Byte = 0x03
        const val NOTIFICATION_ATTRIBUTE_MESSAGE_SIZE: Byte = 0x04
        const val NOTIFICATION_ATTRIBUTE_DATE: Byte = 0x05
        const val NOTIFICATION_ATTRIBUTE_POSITIVE_ACTION_LABEL: Byte = 0x06
        const val NOTIFICATION_ATTRIBUTE_NEGATIVE_ACTION_LABEL: Byte = 0x07
    }
    
    /**
     * 解析 ANCS 通知数据
     * @param data 通知数据字节数组
     * @return 解析结果，包含事件ID、类别ID、通知UID等信息
     */
    fun parseAncsNotification(data: ByteArray): AncsNotificationInfo? {
        if (data.size < 8) {
            Log.e(TAG, "通知数据长度不足: ${data.size}")
            return null
        }
        
        val eventId = data[0].toInt() and 0xFF
        val eventFlags = data[1].toInt() and 0xFF
        val categoryId = data[2].toInt() and 0xFF
        val categoryCount = data[3].toInt() and 0xFF
        val notificationUid = (data[4].toInt() and 0xFF) or 
                ((data[5].toInt() and 0xFF) shl 8) or 
                ((data[6].toInt() and 0xFF) shl 16) or 
                ((data[7].toInt() and 0xFF) shl 24)
        
        return AncsNotificationInfo(
            eventId = eventId,
            eventFlags = eventFlags,
            categoryId = categoryId,
            categoryCount = categoryCount,
            notificationUid = notificationUid,
            eventType = getEventTypeString(eventId),
            category = getCategoryString(categoryId)
        )
    }
    
    /**
     * 构建获取通知详细内容的命令
     * @param notificationUid 通知UID
     * @return 命令字节数组
     */
    fun buildGetNotificationAttributesCommand(notificationUid: Int): ByteArray {
        val command = ByteArray(14)
        command[0] = COMMAND_GET_NOTIFICATION_ATTRIBUTES
        command[1] = (notificationUid and 0xFF).toByte()
        command[2] = ((notificationUid shr 8) and 0xFF).toByte()
        command[3] = ((notificationUid shr 16) and 0xFF).toByte()
        command[4] = ((notificationUid shr 24) and 0xFF).toByte()
        command[5] = NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER
        command[6] = 0xFF.toByte()
        command[7] = 0xFF.toByte()
        command[8] = NOTIFICATION_ATTRIBUTE_TITLE
        command[9] = 0xFF.toByte()
        command[10] = 0xFF.toByte()
        command[11] = NOTIFICATION_ATTRIBUTE_MESSAGE
        command[12] = 0xFF.toByte()
        command[13] = 0xFF.toByte()
        return command
    }
    
    /**
     * 构建执行通知操作的命令
     * @param notificationUid 通知UID
     * @param actionId 操作ID
     * @return 命令字节数组
     */
    fun buildPerformNotificationActionCommand(notificationUid: Int, actionId: Byte): ByteArray {
        val command = ByteArray(5)
        command[0] = COMMAND_PERFORM_NOTIFICATION_ACTION
        command[1] = (notificationUid and 0xFF).toByte()
        command[2] = ((notificationUid shr 8) and 0xFF).toByte()
        command[3] = ((notificationUid shr 16) and 0xFF).toByte()
        command[4] = actionId
        return command
    }
    
    /**
     * 解析通知详细内容
     * @param data 详细内容数据
     * @return 解析结果，包含应用标识、标题、消息等信息
     */
    fun parseNotificationDetails(data: ByteArray?): NotificationDetails? {
        if (data == null || data.size < 5) {
            Log.e(TAG, "通知详细内容数据长度不足或为null")
            return null
        }
        
        var offset = 0
        val commandId = data[offset++]
        val notificationUid = (data[offset].toLong() and 0xFFL) or
                ((data[offset + 1].toLong() and 0xFFL) shl 8) or
                ((data[offset + 2].toLong() and 0xFFL) shl 16) or
                ((data[offset + 3].toLong() and 0xFFL) shl 24)
        offset += 4
        
        var appIdentifier = "N/A"
        var title = "N/A"
        var message = "N/A"
        val attributes = mutableMapOf<String, String>()
        
        while (offset < data.size) {
            if (data.size < offset + 3) {
                Log.e(TAG, "通知详细内容数据不完整，无法解析更多属性")
                break
            }
            
            val attributeId = data[offset++].toInt() and 0xFF
            val length = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            
            if (data.size < offset + length) {
                Log.e(TAG, "通知详细内容数据不完整，属性内容长度不足")
                break
            }
            
            var content: String
            try {
                content = String(data, offset, length, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "解析属性内容时发生编码错误: ${e.message}")
                content = "[解码失败]"
            }
            offset += length
            
            val attributeName = getAttributeName(attributeId)
            attributes[attributeName] = content
            
            when (attributeId) {
                NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER.toInt() -> appIdentifier = content
                NOTIFICATION_ATTRIBUTE_TITLE.toInt() -> title = content
                NOTIFICATION_ATTRIBUTE_MESSAGE.toInt() -> message = content
            }
        }
        
        return NotificationDetails(
            commandId = commandId,
            notificationUid = notificationUid,
            appIdentifier = appIdentifier,
            title = title,
            message = message,
            attributes = attributes
        )
    }
    
    /**
     * 获取事件类型字符串
     */
    private fun getEventTypeString(eventId: Int): String {
        return when (eventId) {
            0 -> "添加"
            1 -> "修改"
            2 -> "删除"
            else -> "未知"
        }
    }
    
    /**
     * 获取类别字符串
     */
    private fun getCategoryString(categoryId: Int): String {
        return when (categoryId) {
            0 -> "其他"
            1 -> "来电"
            2 -> "未接来电"
            3 -> "语音邮件"
            4 -> "社交"
            5 -> "日程"
            6 -> "邮件"
            7 -> "新闻"
            8 -> "健康与健身"
            9 -> "商业/金融"
            10 -> "位置"
            11 -> "娱乐"
            else -> "未知类别"
        }
    }
    
    /**
     * 获取属性名称
     */
    private fun getAttributeName(attributeId: Int): String {
        return when (attributeId) {
            NOTIFICATION_ATTRIBUTE_APP_IDENTIFIER.toInt() -> "应用"
            NOTIFICATION_ATTRIBUTE_TITLE.toInt() -> "标题"
            NOTIFICATION_ATTRIBUTE_SUBTITLE.toInt() -> "副标题"
            NOTIFICATION_ATTRIBUTE_MESSAGE.toInt() -> "内容"
            NOTIFICATION_ATTRIBUTE_DATE.toInt() -> "时间"
            else -> "未知属性"
        }
    }
    
    /**
     * 检查是否为新的社交通知
     */
    fun isNewSocialNotification(eventId: Int, categoryId: Int): Boolean {
        return eventId == 0 && categoryId == 4
    }
    
    /**
     * 获取 ANCS 服务
     */
    fun getAncsService(gatt: BluetoothGatt): BluetoothGattService? {
        return gatt.getService(ANCS_SERVICE_UUID)
    }
    
    /**
     * 获取控制点特征
     */
    fun getControlPointCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val ancsService = getAncsService(gatt)
        return ancsService?.getCharacteristic(CONTROL_POINT_UUID)
    }
    
    /**
     * 获取通知源特征
     */
    fun getNotificationSourceCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val ancsService = getAncsService(gatt)
        return ancsService?.getCharacteristic(NOTIFICATION_SOURCE_UUID)
    }
    
    /**
     * 获取数据源特征
     */
    fun getDataSourceCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val ancsService = getAncsService(gatt)
        return ancsService?.getCharacteristic(DATA_SOURCE_UUID)
    }
}

/**
 * ANCS 通知信息数据类
 */
data class AncsNotificationInfo(
    val eventId: Int,
    val eventFlags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    val notificationUid: Int,
    val eventType: String,
    val category: String
)

/**
 * 通知详细内容数据类
 */
data class NotificationDetails(
    val commandId: Byte,
    val notificationUid: Long,
    val appIdentifier: String,
    val title: String,
    val message: String,
    val attributes: Map<String, String>
) 