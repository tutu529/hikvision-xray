@echo off
rem 注册并启动 Windows 服务(需要 bin 目录下放置 WinSW-x64.exe 与 hikvision-xray-service.xml)
cd /d %~dp0
WinSW-x64.exe install
WinSW-x64.exe start
echo 服务已安装并启动,查看状态: WinSW-x64.exe status
pause
