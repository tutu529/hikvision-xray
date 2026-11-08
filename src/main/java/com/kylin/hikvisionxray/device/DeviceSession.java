package com.kylin.hikvisionxray.device;

import com.kylin.hikvisionxray.config.BridgeProperties;
import NetSDKDemo.HCNetSDK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 单台安检机的连接会话,独立线程管理完整生命周期:
 * 登录 → 布防 → 等待断线 → 撤防/注销 → 指数退避重连。
 * <p>
 * 一台设备故障不影响其他设备。
 */
public class DeviceSession implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceSession.class);

    private static final int INITIAL_BACKOFF_MS = 5_000;
    private static final int MAX_BACKOFF_MS = 300_000;

    public enum State {DISCONNECTED, CONNECTING, ARMED, STOPPED}

    private final HCNetSDK hcNetSDK;
    private final BridgeProperties.Device config;
    private final DeviceManager manager;

    private final Object monitor = new Object();

    private volatile State state = State.DISCONNECTED;
    private volatile boolean stopped = false;
    private volatile boolean disconnectFlag = false;
    private volatile int userID = -1;
    private volatile int alarmHandle = -1;
    private volatile LocalDateTime lastAlarmTime;

    public DeviceSession(HCNetSDK hcNetSDK, BridgeProperties.Device config, DeviceManager manager) {
        this.hcNetSDK = hcNetSDK;
        this.config = config;
        this.manager = manager;
    }

    public BridgeProperties.Device getConfig() {
        return config;
    }

    public State getState() {
        return state;
    }

    public LocalDateTime getLastAlarmTime() {
        return lastAlarmTime;
    }

    public void touchLastAlarm() {
        lastAlarmTime = LocalDateTime.now();
    }

    /**
     * 外部(异常回调/断线报警)通知断线,触发重连。可在任意线程调用,不阻塞。
     */
    public void notifyDisconnect() {
        disconnectFlag = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public void stop() {
        stopped = true;
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("device-" + config.getId());
        int backoff = INITIAL_BACKOFF_MS;
        log.info("设备 {} ({}) 会话启动", config.getId(), config.getIp());

        while (!stopped) {
            state = State.CONNECTING;
            try {
                userID = login();
                alarmHandle = setupAlarm(userID);
                manager.register(userID, this);
                state = State.ARMED;
                backoff = INITIAL_BACKOFF_MS; // 连接成功后重置退避
                log.info("设备 {} ({}) 登录并布防成功 userID={} alarmHandle={}", config.getId(), config.getIp(), userID, alarmHandle);
                waitForDisconnect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                // 必须捕获 Throwable:JNA 字段顺序等错误以 Error 抛出,catch(Exception) 拦不住,
                // 线程会静默死亡且无任何日志(本次"开始登录"后卡死即此原因)。
                log.warn("设备 {} ({}) 登录/布防失败: {}", config.getId(), config.getIp(), e.getMessage());
            }

            // 清理本轮回话
            cleanup();

            if (stopped) {
                break;
            }
            state = State.DISCONNECTED;
            log.info("设备 {} ({}) 将在 {}s 后重连", config.getId(), config.getIp(), backoff / 1000);
            sleepQuietly(backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            disconnectFlag = false;
        }

        state = State.STOPPED;
        log.info("设备 {} ({}) 会话退出", config.getId(), config.getIp());
    }

    private int login() {
        // 注意:切勿打印 JNA Structure(toString 会递归反射几十万个嵌套字段,导致线程长时间卡死)
        HCNetSDK.NET_DVR_USER_LOGIN_INFO loginInfo = new HCNetSDK.NET_DVR_USER_LOGIN_INFO();
        HCNetSDK.NET_DVR_DEVICEINFO_V40 deviceInfo = new HCNetSDK.NET_DVR_DEVICEINFO_V40();

        byte[] address = new byte[HCNetSDK.NET_DVR_DEV_ADDRESS_MAX_LEN];
        byte[] ipBytes = config.getIp().getBytes();
        System.arraycopy(ipBytes, 0, address, 0, Math.min(ipBytes.length, address.length));
        loginInfo.sDeviceAddress = address;

        byte[] userName = new byte[HCNetSDK.NET_DVR_LOGIN_USERNAME_MAX_LEN];
        System.arraycopy(config.getUsername().getBytes(), 0, userName, 0,
                Math.min(config.getUsername().getBytes().length, userName.length));
        loginInfo.sUserName = userName;
        System.arraycopy(config.getPassword().getBytes(), 0, loginInfo.sPassword, 0,
                Math.min(config.getPassword().getBytes().length, loginInfo.sPassword.length));

        loginInfo.wPort = (short) config.getPort();
        loginInfo.bUseAsynLogin = false;   // 同步登录
        loginInfo.byLoginMode = 0;         // SDK 私有协议
        log.info("开始登录");
        int id = hcNetSDK.NET_DVR_Login_V40(loginInfo, deviceInfo);
        if (id == -1) {
            throw new IllegalStateException("登录失败,错误码 " + hcNetSDK.NET_DVR_GetLastError());
        }
        return id;
    }

    private int setupAlarm(int userId) {
        HCNetSDK.NET_DVR_SETUPALARM_PARAM param = new HCNetSDK.NET_DVR_SETUPALARM_PARAM();
        param.dwSize = param.size();
        param.byLevel = 0;           // 布防等级:0-高
        param.byAlarmInfoType = 1;   // 新报警信息
        param.byDeployType = 0;      // 客户端布防
        param.write();

        int handle = hcNetSDK.NET_DVR_SetupAlarmChan_V41(userId, param);
        if (handle == -1) {
            throw new IllegalStateException("布防失败,错误码 " + hcNetSDK.NET_DVR_GetLastError());
        }
        return handle;
    }

    private void waitForDisconnect() throws InterruptedException {
        synchronized (monitor) {
            while (!disconnectFlag && !stopped) {
                monitor.wait(60_000L);
            }
        }
    }

    private void cleanup() {
        manager.unregister(userID);
        if (alarmHandle >= 0) {
            hcNetSDK.NET_DVR_CloseAlarmChan(alarmHandle);
            alarmHandle = -1;
        }
        if (userID >= 0) {
            hcNetSDK.NET_DVR_Logout(userID);
            userID = -1;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "DeviceSession{" + config + ", state=" + state + '}';
    }
}
