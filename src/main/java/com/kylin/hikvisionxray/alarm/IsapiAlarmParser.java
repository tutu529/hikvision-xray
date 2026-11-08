package com.kylin.hikvisionxray.alarm;

import com.kylin.hikvisionxray.config.BridgeProperties;
import com.sun.jna.Pointer;
import NetSDKDemo.HCNetSDK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * COMM_ISAPI_ALARM (0x6009) 报警解析。
 *
 * 前提:SDK 初始化后已设置 byAlarmJsonPictureSeparate=1,
 * 报警以 NET_DVR_ALARM_ISAPI_INFO 结构上传:JSON 数据与图片分离。
 *
 * 注意:本类仅在 SDK 回调线程中执行,只做内存拷贝,不做任何 IO。
 */
public final class IsapiAlarmParser {

    private static final Logger log = LoggerFactory.getLogger(IsapiAlarmParser.class);

    /** JSON 数据长度上限(防御异常数据) */
    private static final int MAX_JSON_LEN = 16 * 1024 * 1024;

    private IsapiAlarmParser() {
    }

    /**
     * 从报警缓冲区提取事件。仅 COMM_ISAPI_ALARM 有效,其他命令返回 null。
     * 必须在 SDK 回调内调用(缓冲区仅在回调期间有效)。
     */
    public static AlarmEvent extract(int lCommand, HCNetSDK.NET_DVR_ALARMER pAlarmer,
                                     Pointer pAlarmInfo, BridgeProperties.Device device) {
        if (lCommand != HCNetSDK.COMM_ISAPI_ALARM) {
            return null;
        }

        HCNetSDK.NET_DVR_ALARM_ISAPI_INFO stru = new HCNetSDK.NET_DVR_ALARM_ISAPI_INFO();
        stru.write();
        Pointer pStru = stru.getPointer();
        pStru.write(0, pAlarmInfo.getByteArray(0, stru.size()), 0, stru.size());
        stru.read();

        // JSON 判图数据
        byte[] jsonBytes;
        int jsonLen = stru.dwAlarmDataLen;
        if (jsonLen <= 0 || jsonLen > MAX_JSON_LEN || stru.pAlarmData == null) {
            jsonBytes = new byte[0];
        } else {
            jsonBytes = stru.pAlarmData.getByteArray(0, jsonLen);
        }

        AlarmEvent event = new AlarmEvent(
                device.getId(), device.getLineId(),
                device.getName() != null ? device.getName() : device.getId(),
                device.getIp(), LocalDateTime.now(), jsonBytes);

        // 图片变长部分:byPicturesNumber 个 NET_DVR_ALARM_ISAPI_PICDATA 连续排列
        int picNum = stru.byPicturesNumber & 0xFF;
        if (picNum > 0 && stru.pPicPackData != null) {
            HCNetSDK.NET_DVR_ALARM_ISAPI_PICDATA picStru = new HCNetSDK.NET_DVR_ALARM_ISAPI_PICDATA();
            int picStruSize = picStru.size();
            for (int i = 0; i < picNum; i++) {
                picStru.write();
                Pointer pPic = picStru.getPointer();
                pPic.write(0, stru.pPicPackData.getByteArray((long) i * picStruSize, picStruSize), 0, picStruSize);
                picStru.read();

                int picLen = picStru.dwPicLen;
                if (picLen <= 0 || picStru.pPicData == null) {
                    log.warn("设备 {} 报警图片 {} 数据为空,跳过", device.getId(), i);
                    continue;
                }
                byte[] picData = picStru.pPicData.getByteArray(0, picLen);
                String filename = decodeFilename(picStru.szFilename);
                event.addPicture(new AlarmEvent.Picture(filename, picData));
            }
        }

        if (event.getPictures().isEmpty()) {
            log.warn("设备 {} 报警无有效图片数据 (jsonLen={}, picNum={})", device.getId(), jsonLen, picNum);
        }
        return event;
    }

    /**
     * 图片原始文件名解码。设备侧文件名一般为 GBK 编码,失败时降级 UTF-8。
     */
    private static String decodeFilename(byte[] raw) {
        int len = 0;
        while (len < raw.length && raw[len] != 0) {
            len++;
        }
        String s = new String(raw, 0, len, java.nio.charset.Charset.forName("GBK"));
        // GBK 解码不会抛异常,粗判乱码则换 UTF-8
        if (s.indexOf('�') >= 0) {
            s = new String(raw, 0, len, StandardCharsets.UTF_8);
        }
        return s.trim();
    }
}
