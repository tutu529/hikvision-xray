package com.kylin.hikvisionxray.queue;

import com.kylin.hikvisionxray.alarm.AlarmEvent;
import com.kylin.hikvisionxray.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/**
 * 图片留存存储(审计/备份用),按设备/日期分目录,到期清理。
 *
 * 目录结构:images/{deviceId}/{yyyyMMdd}/{yyyyMMddHHmmss}_{序号}_{原始文件名}.jpg
 * 同目录保存原始 ISAPI 判图 JSON: images/{deviceId}/{yyyyMMdd}/{yyyyMMddHHmmss}_{序号}.json
 */
@Component
public class ImageStore {

    private static final Logger log = LoggerFactory.getLogger(ImageStore.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path imageDir;

    public ImageStore(BridgeProperties properties) {
        this.imageDir = Paths.get(properties.getHttp().getImageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(imageDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建留存目录: " + imageDir, e);
        }
    }

    /** 留存一次报警的全部图片 + 原始 JSON。失败仅记日志,不阻塞发送链路。 */
    public void save(AlarmEvent event) {
        String day = event.getReceivedAt().format(DATE_FMT);
        String timePrefix = event.getReceivedAt().format(TIME_FMT);
        Path dayDir = imageDir.resolve(event.getDeviceId()).resolve(day);
        try {
            Files.createDirectories(dayDir);
        } catch (IOException e) {
            log.error("创建留存目录失败: {}", dayDir, e);
            return;
        }

        for (int i = 0; i < event.getPictures().size(); i++) {
            AlarmEvent.Picture pic = event.getPictures().get(i);
            String safeName = sanitize(pic.getFilename());
            String baseName = timePrefix + "_" + String.format("%02d", i);
            Path imgFile = dayDir.resolve(baseName + (safeName.isEmpty() ? ".jpg" : "_" + safeName));
            try {
                Files.write(imgFile, pic.getData());
            } catch (IOException e) {
                log.error("留存图片失败: {}", imgFile, e);
            }
        }

        // 原始 ISAPI JSON 留存(一次报警一份)
        if (event.getAlarmJson() != null && event.getAlarmJson().length > 0) {
            Path jsonFile = dayDir.resolve(timePrefix + "_" + String.format("%02d", 0) + ".json");
            try {
                Files.write(jsonFile, event.getAlarmJson());
            } catch (IOException e) {
                log.error("留存报警 JSON 失败: {}", jsonFile, e);
            }
        }
    }

    /** 删除 retentionDays 天前的留存目录(按设备遍历)。 */
    public void cleanBefore(int retentionDays) {
        LocalDate threshold = LocalDate.now().minusDays(retentionDays);
        if (!Files.isDirectory(imageDir)) {
            return;
        }
        try (DirectoryStream<Path> deviceDirs = Files.newDirectoryStream(imageDir)) {
            for (Path deviceDir : deviceDirs) {
                if (!Files.isDirectory(deviceDir)) {
                    continue;
                }
                try (DirectoryStream<Path> dayDirs = Files.newDirectoryStream(deviceDir)) {
                    for (Path dayDir : dayDirs) {
                        if (!Files.isDirectory(dayDir)) {
                            continue;
                        }
                        try {
                            LocalDate day = LocalDate.parse(dayDir.getFileName().toString(), DATE_FMT);
                            if (day.isBefore(threshold)) {
                                deleteTree(dayDir);
                                log.info("清理过期留存目录: {}", dayDir);
                            }
                        } catch (Exception ignored) {
                            // 非日期格式目录跳过
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("清理留存目录失败", e);
        }
    }

    private void deleteTree(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除文件失败: {}", p, e);
                }
            });
        }
    }

    /** 清洗文件名中的路径分隔符等非法字符。 */
    private String sanitize(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }
}
