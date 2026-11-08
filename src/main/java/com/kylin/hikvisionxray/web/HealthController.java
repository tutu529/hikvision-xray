package com.kylin.hikvisionxray.web;

import com.kylin.hikvisionxray.device.DeviceManager;
import com.kylin.hikvisionxray.monitor.Stats;
import com.kylin.hikvisionxray.queue.SpoolStore;
import com.kylin.hikvisionxray.sender.AlarmPipeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端口健康检查。
 * GET /healthz — 设备状态、队列积压、spool 待发数、发送统计。
 */
@RestController
public class HealthController {

    private final DeviceManager deviceManager;
    private final AlarmPipeline pipeline;
    private final SpoolStore spoolStore;
    private final Stats stats;
    private final LocalDateTime startedAt = LocalDateTime.now();

    public HealthController(DeviceManager deviceManager, AlarmPipeline pipeline,
                            SpoolStore spoolStore, Stats stats) {
        this.deviceManager = deviceManager;
        this.pipeline = pipeline;
        this.spoolStore = spoolStore;
        this.stats = stats;
    }

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("startedAt", startedAt.toString());
        result.put("now", LocalDateTime.now().toString());

        List<Map<String, Object>> devices = deviceManager.statuses().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.id());
                    m.put("name", s.name());
                    m.put("ip", s.ip());
                    m.put("lineId", s.lineId());
                    m.put("enabled", s.enabled());
                    m.put("state", s.state().name());
                    m.put("lastAlarmTime", s.lastAlarmTime() == null ? null : s.lastAlarmTime().toString());
                    return m;
                })
                .toList();
        result.put("devices", devices);

        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("inbound", pipeline.inboundSize());
        queue.put("outbound", pipeline.outboundSize());
        queue.put("spoolPending", spoolStore.countPending());
        result.put("queue", queue);

        Map<String, Object> statMap = new LinkedHashMap<>();
        statMap.put("receivedAlarms", stats.getReceivedAlarms());
        statMap.put("receivedPictures", stats.getReceivedPictures());
        statMap.put("dropped", stats.getDropped());
        statMap.put("sentOk", stats.getSentOk());
        statMap.put("sentFail", stats.getSentFail());
        statMap.put("spoolSaveFail", stats.getSpoolSaveFail());
        result.put("stats", statMap);

        return result;
    }
}
