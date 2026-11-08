# 海康安检机桥接服务 — 部署文档

架构说明见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 1. 服务器要求

- Windows Server(任意近年版本),64 位
- JRE 21(或 JDK 21 的 jre 目录)
- 与安检机网络互通(SDK 端口 8000 可达)
- 与应用服务器网络互通(业务接口可达)
- 留存磁盘余量建议 ≥ 500GB(按 3000 张/天 × 30 天估算,上线后按真实单张大小校准)

## 2. 目录结构

```
C:\hikvision-bridge\
├─ hikvision-xray.jar        # 构建产物(Maven package)
├─ config\
│  └─ application.yml        # 现场配置(覆盖 jar 内默认模板,改后重启生效,Spring Boot 自动加载)
├─ lib\                      # 海康 SDK 动态库(必须完整)
│  ├─ HCNetSDK.dll
│  ├─ HCCore.dll
│  ├─ hlog.dll
│  ├─ hpr.dll
│  ├─ zlib1.dll
│  ├─ libcrypto-1_1-x64.dll
│  ├─ libssl-1_1-x64.dll
│  └─ HCNetSDKCom\            # SDK 组件目录(整目录拷贝,报警等功能依赖 HCAlarm.dll 等组件)
├─ bin\
│  ├─ WinSW-x64.exe          # 从 https://github.com/winsw/winsw/releases 下载
│  ├─ hikvision-xray-service.xml
│  ├─ install-service.bat
│  └─ start.bat              # 前台调试用
├─ images\                   # 图片留存(自动创建,保留 30 天)
├─ spool\                    # 待发队列(自动创建,发送成功即删)
├─ logs\                     # 应用日志(自动创建)
└─ sdklog\                   # SDK 日志(自动创建)
```

DLL 来源:海康 SDK 开发包 `HCNetSDKV6.1.11.30_build20260805_Win64_ZH/库文件/` 目录,
复制上表列出的 7 个 DLL 及整个 `HCNetSDKCom` 组件目录即可(PlayCtrl.dll、SuperRender.dll 等预览库不需要)。

## 3. 部署步骤

1. 按上述结构放置 jar、application.yml、DLL、WinSW
2. 编辑 `config/application.yml`:填写安检机 IP/用户名/密码、业务接口 `target-url`
3. 确认 application.yml 中 `line-id` 与业务系统线号一致(1 号线 = "1",2 号线 = "2")
4. 前台试运行验证:
   ```
   cd C:\hikvision-bridge
   java -jar hikvision-xray.jar
   ```
   看到日志"设备 x (ip) 登录并布防成功"即对接成功
5. 验证管理端口:`http://localhost:4084/healthz`
6. 注册 Windows 服务(管理员权限运行 `bin\install-service.bat`),之后开机自启、崩溃自动重启

## 4. 验证清单

- [ ] healthz 中两台设备 state = ARMED
- [ ] 安检机过包后,业务系统收到图片(image + lineId 正确)
- [ ] 图片留存目录按设备/日期生成文件
- [ ] 停掉业务系统,观察桥接日志慢重试(60s 一次);恢复业务系统,图片自动补发
- [ ] 拔掉一台安检机网线:该设备会话进入重连,另一台不受影响;恢复后自动重新布防
- [ ] 重启桥接服务:spool 中未发送图片自动续传

## 5. 运维操作

| 操作 | 命令/方式 |
|---|---|
| 查看服务状态 | `bin\WinSW-x64.exe status` |
| 停止/启动服务 | `bin\WinSW-x64.exe stop` / `start` |
| 增删安检机 | 编辑 `application.yml` 的 devices 列表,重启服务 |
| 临时停用某台 | 对应设备 `enabled: false`,重启服务 |
| 查看队列积压 | healthz 的 queue.spoolPending |
| 日志 | `logs\bridge.log`(当天)、`logs\bridge.YYYY-MM-DD.N.log`(按天+大小归档,保留 30 天,总量 2GB)、`sdklog\`(SDK) |

## 6. 常见问题

| 现象 | 处理 |
|---|---|
| 启动报"找不到 HCNetSDK.dll" | lib 目录不完整或工作目录不对(必须以安装根目录为工作目录) |
| 登录失败错误码 7 | 网络不通/端口错/设备拒绝,检查 IP、8000 端口、用户名密码 |
| 布防失败错误码 23 | 设备不支持的布防参数,核对 SDK 版本与设备固件 |
| 收到报警但业务系统没图 | 查 bridge.log 发送日志;curl 业务接口验证连通性 |
| spoolPending 持续增长 | 业务系统宕机或返回非 200,查 bridge.log 与业务接口 |
