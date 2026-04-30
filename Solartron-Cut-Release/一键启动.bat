@echo off
chcp 65001 >nul
setlocal
title Solartron Cut - Optimization Server

echo ========================================
echo   Solartron Cut - Optimization Server
echo   Starting backend service...
echo ========================================
echo.
echo Please keep this window open while using the web page.
echo If the page is not ready, wait a few seconds and refresh.
echo.

start http://localhost:8081

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar app.jar --app.auto-open-browser=true

pause
