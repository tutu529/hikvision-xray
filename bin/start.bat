@echo off
rem 海康安检机桥接服务 - 前台启动(调试用,生产环境请用 WinSW 注册 Windows 服务)
cd /d %~dp0..
rem Spring Boot 自动加载工作目录下 config/application.yml
java -jar hikvision-xray.jar
pause
