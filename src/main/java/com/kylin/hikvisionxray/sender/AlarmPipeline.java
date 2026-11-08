package com.kylin.hikvisionxray.sender;

import com.kylin.hikvisionxray.alarm.AlarmEvent;
import com.kylin.hikvisionxray.config.BridgeProperties;
import com.kylin.hikvisionxray.monitor.Stats;
import com.kylin.hikvisionxray.queue.ImageStore;
import com.kylin.hikvisionxray.queue.SendUnit;
import com.kylin.hikvisionxray.queue.SpoolStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 报警处理核心管线。
 *
 * SDK 回调 ──offer──▶ inboundQueue ──▶ InboundThread(落盘/拆分)
 *                                          │
 *                                          ▼
 *                                    outboundQueue ──▶ OutboundThreads(HTTP 发送/重试)
 *
 * 可靠性:发送前先 spool 落盘,成功后再删;重启扫描 spool 续传。
 */
@Component
public class AlarmPipeline implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AlarmPipeline.class);

    private final BridgeProperties.Http httpProps;
    private final SpoolStore spoolStore;
    private final ImageStore imageStore;
    private final HttpSender httpSender;
    private final Stats stats;

    private final LinkedBlockingQueue<AlarmEvent> inboundQueue;
    private final LinkedBlockingQueue<SendUnit> outboundQueue;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread inboundThread;
    private Thread[] outboundThreads;

    public AlarmPipeline(BridgeProperties properties, SpoolStore spoolStore, ImageStore imageStore,
                         HttpSender httpSender, Stats stats) {
        this.httpProps = properties.getHttp();
        this.spoolStore = spoolStore;
        this.imageStore = imageStore;
        this.httpSender = httpSender;
        this.stats = stats;
        this.inboundQueue = new LinkedBlockingQueue<>(httpProps.getQueueCapacity());
        this.outboundQueue = new LinkedBlockingQueue<>(httpProps.getQueueCapacity());
    }

    /** 启动管线:恢复 spool,启动处理/发送线程。 */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);

        // 重启续传:恢复上次未发送完成的单元
        List<SendUnit> pending = spoolStore.loadPending();
        for (SendUnit unit : pending) {
            if (!outboundQueue.offer(unit)) {
                log.warn("恢复队列已满,unitId={} 稍后由手动/重启恢复", unit.getUnitId());
            }
        }

        inboundThread = new Thread(this::inboundLoop, "alarm-inbound");
        inboundThread.setDaemon(false);
        inboundThread.start();

        outboundThreads = new Thread[httpProps.getSendThreads()];
        for (int i = 0; i < outboundThreads.length; i++) {
            outboundThreads[i] = new Thread(this::outboundLoop, "alarm-outbound-" + i);
            outboundThreads[i].setDaemon(false);
            outboundThreads[i].start();
        }
        log.info("报警管线已启动:入站线程 1,发送线程 {}", outboundThreads.length);
    }

    /** SDK 回调入口:入站队列满时丢弃并计数(极端保护)。 */
    public void offer(AlarmEvent event) {
        if (!inboundQueue.offer(event)) {
            stats.dropped();
            log.error("入站队列已满,丢弃报警 deviceId={} (图片 {} 张)", event.getDeviceId(), event.getPictures().size());
        }
    }

    public int inboundSize() {
        return inboundQueue.size();
    }

    public int outboundSize() {
        return outboundQueue.size();
    }

    private void inboundLoop() {
        while (running.get()) {
            try {
                AlarmEvent event = inboundQueue.take();
                process(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running.get()) {
                    break;
                }
            } catch (Throwable t) {
                log.error("报警处理异常", t);
            }
        }
        log.info("入站线程退出");
    }

    /** 拆分报警为单图发送单元:spool 落盘(必须先于入队)→ 留存 → 投递发送队列。 */
    private void process(AlarmEvent event) {
        stats.alarmReceived(event.getPictures().size());
        log.info("收到报警 deviceId={} ip={} 图片={} 张 jsonLen={}",
                event.getDeviceId(), event.getDeviceIp(), event.getPictures().size(), event.getAlarmJson().length);

        for (int i = 0; i < event.getPictures().size(); i++) {
            AlarmEvent.Picture pic = event.getPictures().get(i);
            String uploadName = buildUploadName(event, i, pic.getFilename());

            SendUnit unit = SendUnit.create(
                    event.getDeviceId(), event.getLineId(), event.getDeviceName(), event.getDeviceIp(),
                    event.getReceivedAt(), uploadName, pic.getData());

            // 1. spool 落盘(发送前必须先落盘,失败则丢弃该单元并计数,不允许不落盘就发送)
            try {
                spoolStore.save(unit);
            } catch (Exception e) {
                stats.spoolSaveFail();
                log.error("spool 落盘失败,丢弃单元 deviceId={} filename={}", event.getDeviceId(), uploadName, e);
                continue;
            }

            // 2. 入发送队列
            if (!outboundQueue.offer(unit)) {
                log.error("发送队列已满,单元保留在 spool 待重启恢复 unitId={}", unit.getUnitId());
                continue;
            }
        }

        // 3. 留存(不阻塞发送链路,失败仅记日志)
        imageStore.save(event);
    }

    /**
     * 上传文件名,参照截图机约定:captured_{毫秒时间戳}.{扩展名}
     * 单图 = captured_{millis}.jpg;一次报警多图时追加 _2、_3... 保证唯一(接口按 filename 去重)。
     */
    private String buildUploadName(AlarmEvent event, int index, String original) {
        long millis = event.getReceivedAt().atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        String ext = ".jpg";
        if (original != null) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && dot < original.length() - 1) {
                ext = original.substring(dot).toLowerCase();
            }
        }
        String suffix = index == 0 ? "" : "_" + (index + 1);
        return "captured_" + millis + suffix + ext;
    }

    private void outboundLoop() {
        while (running.get()) {
            try {
                SendUnit unit = outboundQueue.take();
                sendWithRetry(unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running.get()) {
                    break;
                }
            } catch (Throwable t) {
                log.error("发送线程异常", t);
            }
        }
        log.info("发送线程 {} 退出", Thread.currentThread().getName());
    }

    /** 发送 + 快/慢两级重试。失败单元延迟后重新入队,直到成功。 */
    private void sendWithRetry(SendUnit unit) {
        try {
            httpSender.send(unit);
            spoolStore.markSent(unit);
            spoolStore.delete(unit);
            stats.sentOk();
        } catch (Exception e) {
            unit.setRetries(unit.getRetries() + 1);
            stats.sentFail();
            long delay = unit.getRetries() <= httpProps.getRetryTimes()
                    ? httpProps.getRetryIntervalMs()
                    : httpProps.getRetryBackoffMs();
            log.warn("发送失败 unitId={} deviceId={} 第 {} 次,{}ms 后重试: {}",
                    unit.getUnitId(), unit.getDeviceId(), unit.getRetries(), delay, e.getMessage());

            sleepUninterruptibly(delay);
            if (running.get() && !outboundQueue.offer(unit)) {
                log.error("重试入队失败,单元保留在 spool 待重启恢复 unitId={}", unit.getUnitId());
            }
        }
    }

    private void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return 100; // SDK 之后、设备登录之前启动;停止顺序相反
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("正在停止报警管线...");
        if (inboundThread != null) {
            inboundThread.interrupt();
        }
        if (outboundThreads != null) {
            for (Thread t : outboundThreads) {
                t.interrupt();
            }
        }
        // 未消费完的队列单元均已落盘 spool,重启后自动续传
        log.info("报警管线已停止(未发送单元保留在 spool,重启续传)");
    }
}
