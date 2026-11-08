package com.kylin.hikvisionxray.sdk;

import NetSDKDemo.HCNetSDK;
import com.sun.jna.Native;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HCNetSDK.dll 加载器。
 *
 * 独立于 SdkManager(避免回调注册链的循环依赖),bean 构造时即加载,
 * DLL 缺失则应用启动失败(fail fast)。
 *
 * 部署要求:lib/ 下放置 HCNetSDK.dll 及其依赖组件库(hlog.dll、hpr.dll、zlib1.dll、
 * libcrypto-1_1-x64.dll、libssl-1_1-x64.dll、HCCore.dll 等)。
 */
@Component
public class SdkNative {

    private final HCNetSDK hcNetSDK;

    public SdkNative() {
        Path dllPath = Paths.get(System.getProperty("user.dir"), "lib", "HCNetSDK.dll")
                .toAbsolutePath().normalize();
        if (!Files.exists(dllPath)) {
            throw new IllegalStateException("找不到 HCNetSDK.dll,请确认部署目录下的 lib 目录完整: "
                    + dllPath + ",实际 lib 目录内容: " + listDir(dllPath.getParent()));
        }
        this.hcNetSDK = Native.loadLibrary(dllPath.toString(), HCNetSDK.class);
    }

    /** 列出目录下文件名(目录不存在时返回提示),便于远程排查部署缺件 */
    private static String listDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "(lib 目录不存在)";
        }
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList().toString();
        } catch (IOException e) {
            return "(无法读取: " + e.getMessage() + ")";
        }
    }

    public HCNetSDK get() {
        return hcNetSDK;
    }
}
