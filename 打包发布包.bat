@echo off
chcp 65001 >nul
setlocal

cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-release.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo [FAILED] 打包失败，请查看上方错误信息。
) else (
    echo [SUCCESS] 打包完成。
)
pause
exit /b %EXIT_CODE%
