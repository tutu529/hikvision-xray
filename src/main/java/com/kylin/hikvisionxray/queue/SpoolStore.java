package com.kylin.hikvisionxray.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kylin.hikvisionxray.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * spool 待发队列落盘存储。
 *
 * 目录结构:spool/{deviceId}/{unitId}.json(元数据) + {unitId}.jpg(图片)
 * 发送成功:标记 sent=true → 删除;服务重启扫描 pending 续传,sent 直接清理。
 */
@Component
public class SpoolStore {

    private static final Logger log = LoggerFactory.getLogger(SpoolStore.class);

    private final Path spoolDir;
    private final ObjectMapper objectMapper;

    public SpoolStore(BridgeProperties properties, ObjectMapper objectMapper) {
        this.spoolDir = Paths.get(properties.getHttp().getSpoolDir()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(spoolDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 spool 目录: " + spoolDir, e);
        }
    }

    /** 发送前落盘:元数据 + 图片。失败抛出异常(调用方不得继续发送)。 */
    public void save(SendUnit unit) throws IOException {
        Path deviceDir = spoolDir.resolve(unit.getDeviceId());
        Files.createDirectories(deviceDir);
        Path metaFile = deviceDir.resolve(unit.getUnitId() + ".json");
        Path imageFile = deviceDir.resolve(unit.getUnitId() + ".jpg");
        Files.write(metaFile, objectMapper.writeValueAsBytes(unit));
        Files.write(imageFile, unit.getImageData());
    }

    /** 发送成功后标记 sent=true(先落状态,后删文件,缩小重复发送窗口)。 */
    public void markSent(SendUnit unit) {
        Path metaFile = spoolDir.resolve(unit.getDeviceId()).resolve(unit.getUnitId() + ".json");
        try {
            unit.setSent(true);
            Files.write(metaFile, objectMapper.writeValueAsBytes(unit));
        } catch (IOException e) {
            log.warn("标记 sent 失败 unitId={},将直接删除 spool 文件", unit.getUnitId(), e);
            delete(unit);
        }
    }

    /** 删除 spool 文件(发送成功或 sent 清理时调用)。 */
    public void delete(SendUnit unit) {
        try {
            Files.deleteIfExists(spoolDir.resolve(unit.getDeviceId()).resolve(unit.getUnitId() + ".json"));
            Files.deleteIfExists(spoolDir.resolve(unit.getDeviceId()).resolve(unit.getUnitId() + ".jpg"));
        } catch (IOException e) {
            log.warn("删除 spool 文件失败 unitId={}", unit.getUnitId(), e);
        }
    }

    /**
     * 启动时扫描:恢复 pending 单元;sent=true 的说明上次已发送成功但删除失败,直接清理。
     */
    public List<SendUnit> loadPending() {
        List<SendUnit> pending = new ArrayList<>();
        if (!Files.isDirectory(spoolDir)) {
            return pending;
        }
        try (DirectoryStream<Path> deviceDirs = Files.newDirectoryStream(spoolDir)) {
            for (Path deviceDir : deviceDirs) {
                if (!Files.isDirectory(deviceDir)) {
                    continue;
                }
                try (DirectoryStream<Path> metas = Files.newDirectoryStream(deviceDir, "*.json")) {
                    for (Path meta : metas) {
                        try {
                            SendUnit unit = objectMapper.readValue(Files.readAllBytes(meta), SendUnit.class);
                            if (unit.isSent()) {
                                delete(unit);
                                continue;
                            }
                            Path imageFile = deviceDir.resolve(unit.getUnitId() + ".jpg");
                            if (!Files.exists(imageFile)) {
                                log.warn("spool 图片缺失,丢弃 unitId={}", unit.getUnitId());
                                delete(unit);
                                continue;
                            }
                            unit.setImageData(Files.readAllBytes(imageFile));
                            pending.add(unit);
                        } catch (Exception e) {
                            log.warn("spool 元数据解析失败,跳过: {}", meta, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("扫描 spool 目录失败", e);
        }
        log.info("spool 恢复:{} 个待发送单元", pending.size());
        return pending;
    }

    /** 统计当前 pending 单元数(healthz 用)。 */
    public int countPending() {
        int count = 0;
        try (DirectoryStream<Path> deviceDirs = Files.newDirectoryStream(spoolDir)) {
            for (Path deviceDir : deviceDirs) {
                if (!Files.isDirectory(deviceDir)) {
                    continue;
                }
                try (DirectoryStream<Path> metas = Files.newDirectoryStream(deviceDir, "*.json")) {
                    for (Path meta : metas) {
                        try {
                            SendUnit unit = objectMapper.readValue(Files.readAllBytes(meta), SendUnit.class);
                            if (!unit.isSent()) {
                                count++;
                            }
                        } catch (Exception ignored) {
                            count++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("统计 spool 失败", e);
        }
        return count;
    }
}
