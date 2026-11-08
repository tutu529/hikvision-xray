# 海康安检机桥接服务 — 架构文档

> 冻结版 2026-10-30。后续开发照此执行,变更需更新本文档。

## 1. 目标

将 N 台海康安检机(X 光机)的智能判图报警(JSON + 图片)实时转发到业务应用的 HTTP 接口。

- 部署环境:Windows Server(Windows 服务方式运行)
- 设备数量:当前 2 台,通过配置文件增删,重启服务生效
- 业务接口:已存在的 `POST /file/api/receive-file`(multipart,一次一张图)

## 2. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.4 | YAML 配置、内嵌 Web(管理端口)、HTTP 客户端、定时任务全有现成方案 |
| 构建 | Maven,fat jar 部署 | 服务器只装 JRE 即可 |
| SDK 接入 | JNA 5.10,复用海康官方 `HCNetSDK.java` 封装(原样复制,不改动) | 10719 行官方绑定,零改写风险 |
| SDK 版本 | HCNetSDK V6.1.11.30 (Win64) | demo 附带 |

## 3. 整体架构

```
config/application.yml
 ├─ bridge.http:   目标地址、超时、重试、目录、留存天数
 └─ bridge.devices[]: 每台设备 { id, line-id, ip, port, username, password, enabled }

主进程 (Spring Boot)
 ├─ DeviceManager: 为每台 enabled 设备启动一个 DeviceSession 线程
 │    └─ DeviceSession: 登录 → 布防 → 等待;失败/断线指数退避重连(5s 起,封顶 5min)
 ├─ SDK 全局回调 (V31, 唯一)
 │    └─ 按 alarmer.lUserID 路由到设备 → 拷贝数据入队 → 立即返回(绝不阻塞)
 ├─ InboundWorker: 解析 COMM_ISAPI_ALARM(JSON + 多图)
 │    ├─ 图片留存落盘 images/{deviceId}/{yyyyMMdd}/
 │    ├─ JSON 一并留存(同目录 .json 文件,审计用)
 │    └─ 拆分为单图 SendUnit → spool 落盘 → 投递发送队列
 ├─ OutboundWorker(线程池): POST multipart → 成功删 spool / 失败退避重试
 │    └─ 服务重启后扫描 spool 续传(sent 状态跳过)
 ├─ RetentionCleaner: 每日删除 retention-days 前的留存图
 └─ 管理端口: GET /healthz(设备状态、队列积压、spool 数)
```

### 数据流

```
安检机 ──ISAPI 报警──▶ SDK 回调线程 ──拷贝──▶ inboundQueue
                                              │
                                              ▼
                                        InboundWorker
                                      (解析/落盘/拆分)
                                              │
                                              ▼
                                        outboundQueue
                                              │
                                              ▼
                                        OutboundWorker
                                     (HTTP multipart POST)
                                              │
                                              ▼
                                   应用 /file/api/receive-file
```

### 可靠性三级保障

1. **内存队列**:inbound/outbound 有界队列,回调永不被阻塞
2. **spool 落盘**:每个 SendUnit 发送前先落盘,发送成功后删除;进程崩溃不丢
3. **重启续传**:启动时扫描 spool 恢复未发送单元;`sent=true` 标记的跳过(极小概率的重复窗口,毫秒级,业务侧可接受)

## 4. 关键设计决策

### 4.1 回调快速返回(铁律)

SDK 回调在 SDK 内部线程执行。回调中只做**字节拷贝 + 入队**,任何文件 IO、HTTP、JSON 解析都不得出现在回调路径上,否则会阻塞整条 SDK 接收链路(两台设备同时受影响)。

### 4.2 多设备路由

SDK 全局只有一个 V31 回调。`NET_DVR_ALARMER.lUserID` 是登录句柄,与配置设备一一对应,以 `Map<lUserID, DeviceSession>` 路由。比按 IP 路由可靠。

### 4.3 JSON/图片分离

初始化后调用 `NET_DVR_SetSDKLocalCfg(17, byAlarmJsonPictureSeparate=1)`,回调收到 `COMM_ISAPI_ALARM (0x6009)`,`NET_DVR_ALARM_ISAPI_INFO` 结构:JSON 判图数据 + N 张图片(每张含原始文件名)。

### 4.4 一次报警拆分为 N 次 HTTP 请求

业务接口一次收一张图(`@RequestParam("image")`),故一次报警按图片数拆成 N 个独立 SendUnit,每个单元独立 spool、独立重试,避免部分成功导致整体重发。

### 4.5 断线重连

双保险:SDK 异常回调(`NET_DVR_SetExceptionCallBack_V30`) + 报警回调中的信号丢失类报警。触发后撤防、注销、指数退避重连。

## 5. 配置契约 (config/application.yml)

```yaml
bridge:
  http:
    target-url: http://应用服务器/file/api/receive-file
    timeout-ms: 5000
    retry-times: 3
    retry-interval-ms: 1000
    spool-dir: ./spool
    image-dir: ./images
    retention-days: 30
  devices:
    - id: xray-01            # 内部唯一标识
      line-id: "1"           # 业务线号,原样传给接口 lineId 字段
      name: "一号安检机"
      ip: 199.199.199.15
      port: 8000
      username: admin
      password: "xxx"
      enabled: true
```

### HTTP 接口契约(对业务方)

```
POST {target-url}
Content-Type: multipart/form-data
  image     = 图片二进制(filename 保留规范化名称)
  lineId    = 设备配置的 line-id(必传)
  eventType = 预留,当前不传
```

### 留存目录

```
images/{deviceId}/{yyyyMMdd}/{yyyyMMddHHmmss}_{序号}_{原始文件名}.jpg
images/{deviceId}/{yyyyMMdd}/{yyyyMMddHHmmss}_{序号}.json   ← 原始 ISAPI 判图 JSON
```

按天分目录,`retention-days`(30 天)到期删除。

## 6. 部署

```
C:\hikvision-bridge\
├─ hikvision-xray.jar        # fat jar
├─ config\application.yml
├─ lib\                      # HCNetSDK.dll + 依赖 DLL(部署时放置)
├─ images\                   # 留存图(自动创建)
├─ spool\                    # 待发队列(自动创建)
├─ logs\
└─ winsw\                    # WinSW 服务包装
```

启动:`java -jar hikvision-xray.jar`

服务化:WinSW 配置开机自启、崩溃自动重启。

管理端口:`GET http://localhost:9090/healthz`

## 7. 已知风险与说明

| 风险 | 说明 |
|---|---|
| 单张图片大小未知 | 上线后用实际数据校准 HTTP 超时、队列上限、磁盘余量。按 3000 张/天 × 30 天,1~5MB/张估算留存 90~450GB,建议留存盘 ≥500GB |
| eventType 语义未定 | 拿到真实 ISAPI 报文后,再定 JSON 字段提取映射规则 |
| 极端崩溃窗口 | 发送成功、删除 spool 前崩溃会导致该图重复发送一次(毫秒级窗口) |
| HCNetSDK.java 原样使用 | 含预览/回放等未用接口,保留原样避免改写风险;依赖 examples.jar 随 lib 部署 |
