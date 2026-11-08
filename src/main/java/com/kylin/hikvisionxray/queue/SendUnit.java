package com.kylin.hikvisionxray.queue;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 单张图片的发送单元。
 * 一次报警含 N 张图则拆成 N 个 SendUnit,各自独立 spool、独立重试。
 */
public class SendUnit {

    /** 单元唯一标识 */
    private String unitId = UUID.randomUUID().toString().replace("-", "");

    private String deviceId;
    private String lineId;
    private String deviceName;
    private String deviceIp;
    private String eventTime;
    /** 上传时使用的文件名 */
    private String imageFilename;
    /** 留存目录中的 JSON 文件名(仅信息) */
    private String metaFilename;
    private String createdAt;
    /** 已尝试发送次数 */
    private int retries;
    /** spool 状态:true 表示已发送成功(重启后直接清理) */
    private boolean sent;

    /** 图片二进制,不参与 spool 元数据序列化(独立文件存放) */
    @JsonIgnore
    private byte[] imageData;

    public String getUnitId() { return unitId; }
    public void setUnitId(String unitId) { this.unitId = unitId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getLineId() { return lineId; }
    public void setLineId(String lineId) { this.lineId = lineId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getDeviceIp() { return deviceIp; }
    public void setDeviceIp(String deviceIp) { this.deviceIp = deviceIp; }
    public String getEventTime() { return eventTime; }
    public void setEventTime(String eventTime) { this.eventTime = eventTime; }
    public String getImageFilename() { return imageFilename; }
    public void setImageFilename(String imageFilename) { this.imageFilename = imageFilename; }
    public String getMetaFilename() { return metaFilename; }
    public void setMetaFilename(String metaFilename) { this.metaFilename = metaFilename; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public boolean isSent() { return sent; }
    public void setSent(boolean sent) { this.sent = sent; }
    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }

    public static SendUnit create(String deviceId, String lineId, String deviceName, String deviceIp,
                                  LocalDateTime eventTime, String imageFilename, byte[] imageData) {
        SendUnit unit = new SendUnit();
        unit.deviceId = deviceId;
        unit.lineId = lineId;
        unit.deviceName = deviceName;
        unit.deviceIp = deviceIp;
        unit.eventTime = eventTime.toString();
        unit.imageFilename = imageFilename;
        unit.imageData = imageData;
        unit.createdAt = LocalDateTime.now().toString();
        return unit;
    }
}
