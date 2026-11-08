package com.kylin.hikvisionxray.alarm;

import com.kylin.hikvisionxray.device.DeviceManager;
import com.kylin.hikvisionxray.device.DeviceSession;
import com.kylin.hikvisionxray.sender.AlarmPipeline;
import com.sun.jna.Pointer;
import NetSDKDemo.HCNetSDK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SDK 全局回调实现(报警回调 V31 + 异常回调)。
 *
 * 铁律:回调在 SDK 内部线程执行,只做内存拷贝与入队,必须快速返回,
 * 任何阻塞都会卡住整条 SDK 接收链路(所有设备)。
 */
@Component
public class AlarmCallbackRouter implements HCNetSDK.FMSGCallBack_V31, HCNetSDK.FExceptionCallBack {

    private static final Logger log = LoggerFactory.getLogger(AlarmCallbackRouter.class);

    private final DeviceManager deviceManager;
    private final AlarmPipeline pipeline;

    public AlarmCallbackRouter(DeviceManager deviceManager, AlarmPipeline pipeline) {
        this.deviceManager = deviceManager;
        this.pipeline = pipeline;
    }

    // ==================== 报警回调 ====================

    @Override
    public boolean invoke(int lCommand, HCNetSDK.NET_DVR_ALARMER pAlarmer, Pointer pAlarmInfo,
                          int dwBufLen, Pointer pUser) {
        try {
            DeviceSession session = route(pAlarmer);
            if (session == null) {
                log.debug("忽略未注册设备的报警 lCommand=0x{}", Integer.toHexString(lCommand));
                return true;
            }

            if (lCommand == HCNetSDK.COMM_ISAPI_ALARM) {
                AlarmEvent event = IsapiAlarmParser.extract(lCommand, pAlarmer, pAlarmInfo, session.getConfig());
                if (event != null) {
                    session.touchLastAlarm();
                    pipeline.offer(event);
                }
            } else if (lCommand == HCNetSDK.COMM_ALARM_V30) {
                // 断线类报警(信号丢失)是异常回调之外的补充触发
                handleAlarmV30(pAlarmInfo, session);
            }
        } catch (Throwable t) {
            log.error("报警回调处理异常", t);
        }
        return true;
    }

    /** 按登录句柄路由,失败按 IP 兜底。 */
    private DeviceSession route(HCNetSDK.NET_DVR_ALARMER pAlarmer) {
        DeviceSession session = deviceManager.lookup(pAlarmer.lUserID);
        if (session != null) {
            return session;
        }
        String ip = new String(pAlarmer.sDeviceIP).trim();
        return ip.isEmpty() ? null : deviceManager.lookupByIp(ip);
    }

    /** COMM_ALARM_V30:dwAlarmType 按位,bit1(值2)为信号丢失。 */
    private void handleAlarmV30(Pointer pAlarmInfo, DeviceSession session) {
        HCNetSDK.NET_DVR_ALARMINFO_V30 stru = new HCNetSDK.NET_DVR_ALARMINFO_V30();
        stru.write();
        Pointer pStru = stru.getPointer();
        pStru.write(0, pAlarmInfo.getByteArray(0, stru.size()), 0, stru.size());
        stru.read();

        if ((stru.dwAlarmType & 0x2) != 0) {
            log.warn("设备 {} ({}) 上报信号丢失,触发重连",
                    session.getConfig().getId(), session.getConfig().getIp());
            session.notifyDisconnect();
        }
    }

    // ==================== 异常回调 ====================

    @Override
    public void invoke(int dwType, int lUserID, int lHandle, Pointer pUser) {
        try {
            DeviceSession session = deviceManager.lookup(lUserID);
            if (session == null) {
                return;
            }
            if (dwType == HCNetSDK.EXCEPTION_EXCHANGE || dwType == HCNetSDK.EXCEPTION_ALARM) {
                log.warn("设备 {} ({}) 异常 type=0x{},触发重连",
                        session.getConfig().getId(), session.getConfig().getIp(), Integer.toHexString(dwType));
                session.notifyDisconnect();
            }
        } catch (Throwable t) {
            log.error("异常回调处理异常", t);
        }
    }
}
