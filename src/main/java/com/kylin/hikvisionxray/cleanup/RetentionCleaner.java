package com.kylin.hikvisionxray.cleanup;

import com.kylin.hikvisionxray.config.BridgeProperties;
import com.kylin.hikvisionxray.queue.ImageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 留存图片定时清理:每天凌晨 2 点删除 retention-days 前的留存目录。
 */
@Component
public class RetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleaner.class);

    private final ImageStore imageStore;
    private final BridgeProperties properties;

    public RetentionCleaner(ImageStore imageStore, BridgeProperties properties) {
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void clean() {
        int retentionDays = properties.getHttp().getRetentionDays();
        log.info("开始清理留存:删除 {} 天前的图片", retentionDays);
        imageStore.cleanBefore(retentionDays);
    }
}
