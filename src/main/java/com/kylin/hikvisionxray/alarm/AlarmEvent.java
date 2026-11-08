package com.kylin.hikvisionxray.alarm;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次安检机报警事件:原始 ISAPI JSON + 图片列表。
 * 在 SDK 回调线程中构造(仅字节拷贝),随后投递到处理管线。
 */
public class AlarmEvent {

    private final String deviceId;
    private final String lineId;
    private final String deviceName;
    private final String deviceIp;
    /** 接收时间 */
    private final LocalDateTime receivedAt;
    /** 原始 ISAPI 判图 JSON(字节,编码 UTF-8) */
    private final byte[] alarmJson;
    private final List<Picture> pictures = new ArrayList<>();

    public AlarmEvent(String deviceId, String lineId, String deviceName, String deviceIp,
                      LocalDateTime receivedAt, byte[] alarmJson) {
        this.deviceId = deviceId;
        this.lineId = lineId;
        this.deviceName = deviceName;
        this.deviceIp = deviceIp;
        this.receivedAt = receivedAt;
        this.alarmJson = alarmJson;
    }

    public String getDeviceId() { return deviceId; }
    public String getLineId() { return lineId; }
    public String getDeviceName() { return deviceName; }
    public String getDeviceIp() { return deviceIp; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public byte[] getAlarmJson() { return alarmJson; }
    public List<Picture> getPictures() { return pictures; }
    public void addPicture(Picture picture) { pictures.add(picture); }

    /** 单张图片 */
    public static class Picture {
        private final String filename;
        private final byte[] data;

        public Picture(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }

        public String getFilename() { return filename; }
        public byte[] getData() { return data; }
    }
}
