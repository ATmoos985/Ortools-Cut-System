@echo off
chcp 65001 >nul
setlocal

title 排程切割优化系统 - 一键打包

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%package-release.ps1"

if not exist "%PS_SCRIPT%" (
    echo [错误] 找不到打包脚本：%PS_SCRIPT%
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo [完成] 打包成功。
) else (
    echo [失败] 打包失败，错误码：%EXIT_CODE%
)
echo.
pause
exit /b %EXIT_CODE%
