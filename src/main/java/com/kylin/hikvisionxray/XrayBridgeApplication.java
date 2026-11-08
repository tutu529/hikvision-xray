package com.kylin.hikvisionxray;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 海康安检机桥接服务入口。
 *
 * 部署启动(Spring Boot 自动加载工作目录下 config/application.yml):
 *   java -jar hikvision-xray.jar
 *
 * 生命周期(由 Spring SmartLifecycle phase 保证):
 *   启动:HCNetSDK 初始化(0)→ 报警管线(100)→ 设备登录布防(200)
 *   停止:设备会话 → 报警管线 → SDK 清理
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class XrayBridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(XrayBridgeApplication.class, args);
    }
}
