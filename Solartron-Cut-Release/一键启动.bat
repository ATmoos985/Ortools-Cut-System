@echo off
chcp 65001 >nul
title 太阳诱电-排程切割优化系统

echo ========================================
echo        排程切割优化系统 - 正在启动
echo ========================================
echo.
echo.
echo 注意：请勿关闭本黑色窗口，这是系统运行的后端服务。
echo 如果页面无法访问，请等待几秒钟再刷新。
echo.

start http://localhost:8081

java -jar app.jar --app.auto-open-browser=true

pause
