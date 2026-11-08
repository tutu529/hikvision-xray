package com.kylin.hikvisionxray.sdk;

import com.kylin.hikvisionxray.alarm.AlarmCallbackRouter;
import NetSDKDemo.HCNetSDK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * HCNetSDK 生命周期管理:初始化、回调注册、清理。
 *
 * 启动顺序(SmartLifecycle phase):SdkManager(0)→ AlarmPipeline(100)→ DeviceManager(200),
 * 停止顺序相反,保证设备会话先退出、管线再停、SDK 最后清理。
 */
@Component
public class SdkManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SdkManager.class);

    /** NET_DVR_LOCAL_CFG_TYPE_GENERAL,设置通用参数(海康头文件中定义的枚举值) */
    private static final int NET_DVR_LOCAL_CFG_TYPE_GENERAL = 17;

    private final SdkNative sdkNative;
    private final AlarmCallbackRouter callbackRouter;

    private volatile boolean running = false;

    public SdkManager(SdkNative sdkNative, AlarmCallbackRouter callbackRouter) {
        this.sdkNative = sdkNative;
        this.callbackRouter = callbackRouter;
    }

    @Override
    public void start() {
        HCNetSDK sdk = sdkNative.get();

        if (!sdk.NET_DVR_Init()) {
            throw new IllegalStateException("NET_DVR_Init 失败,错误码 " + sdk.NET_DVR_GetLastError());
        }

        // 控制登录超时:单次 3s,尝试 3 次(设备不可达时避免登录长时间阻塞)
        sdk.NET_DVR_SetConnectTime(3000, 3);

        // SDK 内部日志
        sdk.NET_DVR_SetLogToFile(3, "./sdklog", false);

        // 全局报警回调(唯一,多设备按 lUserID 路由)
        if (!sdk.NET_DVR_SetDVRMessageCallBack_V31(callbackRouter, null)) {
            throw new IllegalStateException("注册报警回调失败,错误码 " + sdk.NET_DVR_GetLastError());
        }

        // 关键:JSON 透传报警数据与图片分离,分离后走 COMM_ISAPI_ALARM 回调
        HCNetSDK.NET_DVR_LOCAL_GENERAL_CFG generalCfg = new HCNetSDK.NET_DVR_LOCAL_GENERAL_CFG();
        generalCfg.byAlarmJsonPictureSeparate = 1;
        generalCfg.write();
        if (!sdk.NET_DVR_SetSDKLocalCfg(NET_DVR_LOCAL_CFG_TYPE_GENERAL, generalCfg.getPointer())) {
            log.warn("设置 JSON/图片分离失败,错误码 {}", sdk.NET_DVR_GetLastError());
        } else {
            log.info("已启用 JSON/图片分离上传(COMM_ISAPI_ALARM)");
        }

        // 异常回调(断线感知,触发设备会话重连)
        if (!sdk.NET_DVR_SetExceptionCallBack_V30(0xFFFFFFFF, 0, callbackRouter, null)) {
            log.warn("注册异常回调失败,错误码 {}", sdk.NET_DVR_GetLastError());
        }

        running = true;
        log.info("HCNetSDK 初始化完成");
    }

    @Override
    public void stop() {
        sdkNative.get().NET_DVR_Cleanup();
        running = false;
        log.info("HCNetSDK 已清理");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0; // 最先启动,最后停止
    }
}
