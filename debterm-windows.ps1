<#
.SYNOPSIS
    DebTerm - 1-Click Debian Terminal Launcher for Windows 10 & 11
#>

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "    DebTerm Windows Edition - Debian Environment on Windows     " -ForegroundColor Green
Write-Host "    Direct Glibc, APT, GPU Compute & Full Linux POSIX Support   " -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$wslInstalled = Get-Command wsl.exe -ErrorAction SilentlyContinue

if (-not $wslInstalled) {
    Write-Host "[!] WSL is not found on your system." -ForegroundColor Red
    Write-Host "[*] Installing WSL & Debian automatically (Administrator required)..." -ForegroundColor Yellow
    Start-Process powershell -Verb RunAs -ArgumentList "wsl --install -d Debian"
    exit
}

$distros = wsl.exe -l -q 2>$null
if ($distros -match "Debian") {
    Write-Host "[+] Launching Debian Terminal Session..." -ForegroundColor Green
    wsl.exe -d Debian -e /bin/bash --login
} else {
    Write-Host "[*] Debian distribution not yet initialized. Installing Debian..." -ForegroundColor Yellow
    wsl.exe --install -d Debian
    wsl.exe -d Debian -e /bin/bash --login
}
