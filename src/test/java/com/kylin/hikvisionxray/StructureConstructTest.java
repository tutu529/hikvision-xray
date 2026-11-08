package com.kylin.hikvisionxray;

import NetSDKDemo.HCNetSDK;
import org.junit.jupiter.api.Test;

/**
 * 临时诊断:验证海康 JNA 结构体构造是否卡死/栈溢出(复现 Windows 上"开始登录"日志不打印问题)。
 */
public class StructureConstructTest {

    @Test
    void constructLoginStructures() {
        System.out.println("构造 NET_DVR_USER_LOGIN_INFO...");
        long t0 = System.currentTimeMillis();
        HCNetSDK.NET_DVR_USER_LOGIN_INFO loginInfo = new HCNetSDK.NET_DVR_USER_LOGIN_INFO();
        System.out.println("USER_LOGIN_INFO 完成,耗时 " + (System.currentTimeMillis() - t0) + "ms");

        System.out.println("构造 NET_DVR_DEVICEINFO_V40...");
        long t1 = System.currentTimeMillis();
        HCNetSDK.NET_DVR_DEVICEINFO_V40 deviceInfo = new HCNetSDK.NET_DVR_DEVICEINFO_V40();
        System.out.println("DEVICEINFO_V40 完成,耗时 " + (System.currentTimeMillis() - t1) + "ms, size=" + deviceInfo.size());
    }
}
