@echo off
chcp 65001 >nul
setlocal

title 太阳诱电-排程切割优化系统

echo ========================================
echo        排程切割优化系统 - 正在启动
echo ========================================
echo.
echo.
echo 注意：请勿关闭本黑色窗口，这是系统运行的后端服务。
echo 系统会在服务启动完成后自动打开浏览器。
echo.

set "APP_DIR=%~dp0"
set "APP_PORT=8081"
set "CONFIG_FILE=%APP_DIR%application.properties"
set "APP_JAR=%APP_DIR%app.jar"

if not exist "%APP_JAR%" (
    echo [错误] 找不到 app.jar，请确认交付包完整。
    echo.
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Java，请先安装 Java 17 或以上版本。
    echo.
    pause
    exit /b 1
)

if exist "%CONFIG_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do (
        if /i "%%A"=="server.port" set "APP_PORT=%%B"
    )
)

set "APP_URL=http://localhost:%APP_PORT%/"

start "" /b powershell -NoProfile -ExecutionPolicy Bypass -Command "$url='%APP_URL%'; $opened=$false; for ($i=1; $i -le 90; $i++) { try { $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2; if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { Start-Process $url; $opened=$true; break } } catch { Start-Sleep -Seconds 1 } }; if (-not $opened) { Write-Host ('服务启动时间较长，请稍后手动访问：' + $url) }"

cd /d "%APP_DIR%"
java -jar "%APP_JAR%"

pause
