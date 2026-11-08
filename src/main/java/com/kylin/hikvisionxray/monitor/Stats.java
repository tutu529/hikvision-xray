package com.kylin.hikvisionxray.monitor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行统计计数,healthz 展示。
 */
@Component
public class Stats {

    /** 收到的报警次数(COMM_ISAPI_ALARM) */
    private final AtomicLong receivedAlarms = new AtomicLong();
    /** 收到的图片张数 */
    private final AtomicLong receivedPictures = new AtomicLong();
    /** 入站队列满丢弃的报警数 */
    private final AtomicLong dropped = new AtomicLong();
    /** 发送成功张数 */
    private final AtomicLong sentOk = new AtomicLong();
    /** 发送失败次数(含重试失败) */
    private final AtomicLong sentFail = new AtomicLong();
    /** spool 落盘失败(丢数据)计数 */
    private final AtomicLong spoolSaveFail = new AtomicLong();

    public void alarmReceived(int pictureCount) {
        receivedAlarms.incrementAndGet();
        receivedPictures.addAndGet(pictureCount);
    }

    public void dropped() { dropped.incrementAndGet(); }
    public void sentOk() { sentOk.incrementAndGet(); }
    public void sentFail() { sentFail.incrementAndGet(); }
    public void spoolSaveFail() { spoolSaveFail.incrementAndGet(); }

    public long getReceivedAlarms() { return receivedAlarms.get(); }
    public long getReceivedPictures() { return receivedPictures.get(); }
    public long getDropped() { return dropped.get(); }
    public long getSentOk() { return sentOk.get(); }
    public long getSentFail() { return sentFail.get(); }
    public long getSpoolSaveFail() { return spoolSaveFail.get(); }
}
