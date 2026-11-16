@echo off
title DebTerm - PRoot Debian Terminal for Windows
color 0A
cls
echo ================================================================
echo    DebTerm Windows Edition - Debian Environment on Windows
echo    Direct Glibc, APT, GPU Compute & Full Linux POSIX Support
echo ================================================================
echo.

wsl.exe -l -q >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] WSL2 is not enabled on this Windows PC.
    echo [*] Installing Debian on Windows automatically...
    wsl.exe --install -d Debian
    pause
    exit /b
)

echo [*] Launching Debian Terminal...
wsl.exe -d Debian -e /bin/bash --login
