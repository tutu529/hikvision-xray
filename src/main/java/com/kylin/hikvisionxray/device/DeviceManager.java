package com.kylin.hikvisionxray.device;

import com.kylin.hikvisionxray.config.BridgeProperties;
import com.kylin.hikvisionxray.sdk.SdkNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备会话管理:为每台 enabled 设备启动独立 DeviceSession 线程,
 * 维护 userID → DeviceSession 的路由表供报警回调使用。
 */
@Component
public class DeviceManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final SdkNative sdkNative;
    private final BridgeProperties properties;

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, DeviceSession> byUserID = new ConcurrentHashMap<>();
    private final List<Thread> threads = new ArrayList<>();

    private volatile boolean running = false;

    public DeviceManager(SdkNative sdkNative, BridgeProperties properties) {
        this.sdkNative = sdkNative;
        this.properties = properties;
    }

    @Override
    public void start() {
        for (BridgeProperties.Device device : properties.getDevices()) {
            if (!device.isEnabled()) {
                log.info("设备 {} ({}) 已禁用(enabled=false),跳过", device.getId(), device.getIp());
                continue;
            }
            DeviceSession session = new DeviceSession(sdkNative.get(), device, this);
            sessions.put(device.getId(), session);
            Thread thread = new Thread(session, "device-" + device.getId());
            thread.setDaemon(false);
            threads.add(thread);
            thread.start();
        }
        running = true;
        log.info("设备管理器已启动:{} 台设备会话", threads.size());
    }

    @Override
    public void stop() {
        running = false;
        for (DeviceSession session : sessions.values()) {
            session.stop();
        }
        for (Thread thread : threads) {
            try {
                thread.join(15_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("设备管理器已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // SDK 初始化(0)→ 报警管线(100)→ 设备登录布防(200);停止顺序相反
        return 200;
    }

    /** 布防成功后注册路由表(报警回调按 lUserID 路由)。 */
    public void register(int userID, DeviceSession session) {
        byUserID.put(userID, session);
    }

    public void unregister(int userID) {
        byUserID.remove(userID);
    }

    /** 按登录句柄查找会话(报警回调主路由)。 */
    public DeviceSession lookup(int userID) {
        return byUserID.get(userID);
    }

    /** 按设备 IP 兜底查找(部分报警 lUserID 可能无效)。 */
    public DeviceSession lookupByIp(String ip) {
        for (DeviceSession session : sessions.values()) {
            if (session.getConfig().getIp().equals(ip)) {
                return session;
            }
        }
        return null;
    }

    public void notifyDisconnect(int userID) {
        DeviceSession session = byUserID.get(userID);
        if (session != null) {
            session.notifyDisconnect();
        }
    }

    /** healthz 用:各设备运行状态快照。 */
    public List<DeviceStatus> statuses() {
        List<DeviceStatus> list = new ArrayList<>();
        for (BridgeProperties.Device device : properties.getDevices()) {
            DeviceSession session = sessions.get(device.getId());
            list.add(new DeviceStatus(
                    device.getId(), device.getName(), device.getIp(), device.getLineId(),
                    device.isEnabled(),
                    session == null ? DeviceSession.State.DISCONNECTED : session.getState(),
                    session == null ? null : session.getLastAlarmTime()));
        }
        return list;
    }

    /** 设备状态快照(healthz 展示)。 */
    public record DeviceStatus(String id, String name, String ip, String lineId,
                               boolean enabled, DeviceSession.State state, LocalDateTime lastAlarmTime) {
    }
}
