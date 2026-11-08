package com.kylin.hikvisionxray.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * 桥接服务配置,绑定 config.yml 中 bridge 段。
 */
@Validated
@ConfigurationProperties(prefix = "bridge")
public class BridgeProperties {

    @NotNull
    @Valid
    private Http http = new Http();

    @NotNull
    private List<@Valid Device> devices = new ArrayList<>();

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public List<Device> getDevices() {
        return devices;
    }

    public void setDevices(List<Device> devices) {
        this.devices = devices;
    }

    /** HTTP 推送相关配置 */
    public static class Http {
        /** 业务应用接收接口地址 */
        @NotBlank
        private String targetUrl;

        /** HTTP 超时(毫秒) */
        private int timeoutMs = 5000;

        /** 快速重试次数,超过后转入慢重试 */
        private int retryTimes = 3;

        /** 快速重试间隔(毫秒) */
        private int retryIntervalMs = 1000;

        /** 慢重试间隔(毫秒),应用长时间宕机后按此节奏补发 */
        private int retryBackoffMs = 60000;

        /** 发送线程数 */
        private int sendThreads = 2;

        /** 内存队列容量 */
        private int queueCapacity = 10000;

        /** spool 待发队列目录 */
        @NotBlank
        private String spoolDir = "./spool";

        /** 图片留存目录 */
        @NotBlank
        private String imageDir = "./images";

        /** 留存天数 */
        private int retentionDays = 30;

        public String getTargetUrl() { return targetUrl; }
        public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getRetryTimes() { return retryTimes; }
        public void setRetryTimes(int retryTimes) { this.retryTimes = retryTimes; }
        public int getRetryIntervalMs() { return retryIntervalMs; }
        public void setRetryIntervalMs(int retryIntervalMs) { this.retryIntervalMs = retryIntervalMs; }
        public int getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(int retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
        public int getSendThreads() { return sendThreads; }
        public void setSendThreads(int sendThreads) { this.sendThreads = sendThreads; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public String getSpoolDir() { return spoolDir; }
        public void setSpoolDir(String spoolDir) { this.spoolDir = spoolDir; }
        public String getImageDir() { return imageDir; }
        public void setImageDir(String imageDir) { this.imageDir = imageDir; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    /** 单台安检机配置 */
    public static class Device {
        /** 内部唯一标识 */
        @NotBlank
        private String id;

        /** 业务线号,原样传给业务接口 lineId 字段 */
        @NotBlank
        private String lineId;

        /** 设备名称 */
        private String name;

        /** 设备 IP */
        @NotBlank
        private String ip;

        /** SDK 端口,默认 8000 */
        private int port = 8000;

        @NotBlank
        private String username;

        @NotBlank
        private String password;

        /** false 时启动跳过该设备(临时停用) */
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLineId() { return lineId; }
        public void setLineId(String lineId) { this.lineId = lineId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        @Override
        public String toString() {
            return "Device{id='" + id + "', ip='" + ip + "'}";
        }
    }
}
