<#
.SYNOPSIS
    MangaAutoScroller - Android Development Helper Script
.DESCRIPTION
    Build, run, and debug your Android app without opening Android Studio.
.EXAMPLE
    .\run.ps1 build      # Build debug APK
    .\run.ps1 run        # Build and install on connected device
    .\run.ps1 clean      # Clean project
    .\run.ps1 devices    # List connected devices
    .\run.ps1 logcat     # View app logs
    .\run.ps1 log        # View filtered app logs (errors/warnings)
    .\run.ps1 install    # Install existing APK
    .\run.ps1 uninstall  # Uninstall app from device
    .\run.ps1 help       # Show this help
#>

param(
    [Parameter(Position=0)]
    [ValidateSet("build", "run", "clean", "devices", "logcat", "log", "install", "uninstall", "release", "help", "")]
    [string]$Command = "help"
)

# Configuration
$AppPackage = "com.zynt.mangaautoscroller"
$MainActivity = "$AppPackage.MainActivity"
$ApkPath = "app\build\outputs\apk\debug\app-debug.apk"
$GradleWrapper = ".\gradlew.bat"

# Colors for output
function Write-Success { param($Message) Write-Host "✅ $Message" -ForegroundColor Green }
function Write-Info { param($Message) Write-Host "ℹ️  $Message" -ForegroundColor Cyan }
function Write-Warn { param($Message) Write-Host "⚠️  $Message" -ForegroundColor Yellow }
function Write-Err { param($Message) Write-Host "❌ $Message" -ForegroundColor Red }

# Check if ADB is available
function Test-Adb {
    try {
        $null = Get-Command adb -ErrorAction Stop
        return $true
    } catch {
        Write-Err "ADB not found. Please add Android SDK platform-tools to PATH"
        Write-Info "Typically located at: C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools"
        return $false
    }
}

# Get connected device
function Get-ConnectedDevice {
    $devices = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
    if ($devices.Count -eq 0) {
        Write-Err "No devices connected"
        return $null
    }
    $deviceId = ($devices[0] -split '\s+')[0]
    return $deviceId
}

# Show help
function Show-Help {
    Write-Host ""
    Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Magenta
    Write-Host "║       📱 MangaAutoScroller - Development Helper 📱           ║" -ForegroundColor Magenta
    Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Magenta
    Write-Host ""
    Write-Host "USAGE:" -ForegroundColor Yellow
    Write-Host "    .\run.ps1 <command>" -ForegroundColor White
    Write-Host ""
    Write-Host "COMMANDS:" -ForegroundColor Yellow
    Write-Host "    build      " -NoNewline -ForegroundColor Green; Write-Host "Build debug APK"
    Write-Host "    run        " -NoNewline -ForegroundColor Green; Write-Host "Build, install, and launch app"
    Write-Host "    install    " -NoNewline -ForegroundColor Green; Write-Host "Install existing APK to device"
    Write-Host "    uninstall  " -NoNewline -ForegroundColor Green; Write-Host "Uninstall app from device"
    Write-Host "    clean      " -NoNewline -ForegroundColor Green; Write-Host "Clean build files"
    Write-Host "    devices    " -NoNewline -ForegroundColor Green; Write-Host "List connected Android devices"
    Write-Host "    logcat     " -NoNewline -ForegroundColor Green; Write-Host "View ALL app logs (live)"
    Write-Host "    log        " -NoNewline -ForegroundColor Green; Write-Host "View filtered logs (errors/warnings/important)"
    Write-Host "    release    " -NoNewline -ForegroundColor Green; Write-Host "Build release APK"
    Write-Host "    help       " -NoNewline -ForegroundColor Green; Write-Host "Show this help message"
    Write-Host ""
    Write-Host "EXAMPLES:" -ForegroundColor Yellow
    Write-Host "    .\run.ps1 run          # Quick build and run"
    Write-Host "    .\run.ps1 logcat       # Watch all logs"
    Write-Host "    .\run.ps1 log          # Watch important logs only"
    Write-Host ""
}

# Build debug APK
function Invoke-Build {
    Write-Info "Building debug APK..."
    $startTime = Get-Date
    
    & $GradleWrapper assembleDebug --warning-mode all
    
    if ($LASTEXITCODE -eq 0) {
        $elapsed = (Get-Date) - $startTime
        Write-Success "Build completed in $([math]::Round($elapsed.TotalSeconds, 1)) seconds"
        Write-Info "APK: $ApkPath"
        return $true
    } else {
        Write-Err "Build failed!"
        return $false
    }
}

# Build release APK
function Invoke-ReleaseBuild {
    Write-Info "Building release APK..."
    $startTime = Get-Date
    
    & $GradleWrapper assembleRelease --warning-mode all
    
    if ($LASTEXITCODE -eq 0) {
        $elapsed = (Get-Date) - $startTime
        Write-Success "Release build completed in $([math]::Round($elapsed.TotalSeconds, 1)) seconds"
        Write-Info "APK: app\build\outputs\apk\release\app-release.apk"
        return $true
    } else {
        Write-Err "Release build failed!"
        return $false
    }
}

# Clean project
function Invoke-Clean {
    Write-Info "Cleaning project..."
    & $GradleWrapper clean
    
    if ($LASTEXITCODE -eq 0) {
        Write-Success "Project cleaned"
    } else {
        Write-Err "Clean failed!"
    }
}

# List connected devices
function Show-Devices {
    if (-not (Test-Adb)) { return }
    
    Write-Info "Connected Android devices:"
    Write-Host ""
    adb devices -l
    Write-Host ""
    
    $device = Get-ConnectedDevice
    if ($device) {
        # Get device info
        $model = adb -s $device shell getprop ro.product.model 2>$null
        $android = adb -s $device shell getprop ro.build.version.release 2>$null
        $sdk = adb -s $device shell getprop ro.build.version.sdk 2>$null
        
        Write-Host "📱 Active Device Info:" -ForegroundColor Cyan
        Write-Host "   Device ID:    $device"
        Write-Host "   Model:        $model"
        Write-Host "   Android:      $android (SDK $sdk)"
    }
}

# Install APK
function Invoke-Install {
    if (-not (Test-Adb)) { return }
    
    $device = Get-ConnectedDevice
    if (-not $device) { return }
    
    if (-not (Test-Path $ApkPath)) {
        Write-Err "APK not found at $ApkPath"
        Write-Info "Run '.\run.ps1 build' first"
        return
    }
    
    Write-Info "Installing APK to $device..."
    adb -s $device install -r $ApkPath
    
    if ($LASTEXITCODE -eq 0) {
        Write-Success "App installed successfully"
    } else {
        Write-Err "Installation failed!"
    }
}

# Uninstall app
function Invoke-Uninstall {
    if (-not (Test-Adb)) { return }
    
    $device = Get-ConnectedDevice
    if (-not $device) { return }
    
    Write-Info "Uninstalling $AppPackage from $device..."
    adb -s $device uninstall $AppPackage
    
    if ($LASTEXITCODE -eq 0) {
        Write-Success "App uninstalled"
    } else {
        Write-Warn "Uninstall may have failed (app might not be installed)"
    }
}

# Build, install, and launch
function Invoke-Run {
    if (-not (Test-Adb)) { return }
    
    $device = Get-ConnectedDevice
    if (-not $device) { return }
    
    # Build
    if (-not (Invoke-Build)) { return }
    
    # Install
    Write-Info "Installing to $device..."
    adb -s $device install -r $ApkPath
    
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Installation failed!"
        return
    }
    
    # Launch
    Write-Info "Launching app..."
    adb -s $device shell am start -n "$AppPackage/$MainActivity"
    
    if ($LASTEXITCODE -eq 0) {
        Write-Success "App launched! 🚀"
        Write-Host ""
        Write-Info "Tip: Run '.\run.ps1 logcat' in another terminal to see logs"
    } else {
        Write-Err "Failed to launch app"
    }
}

# View all logcat
function Show-Logcat {
    if (-not (Test-Adb)) { return }
    
    $device = Get-ConnectedDevice
    if (-not $device) { return }
    
    Write-Info "Showing logs for $AppPackage (Press Ctrl+C to stop)"
    Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor DarkGray
    
    # Get PID of our app
    $pid = adb -s $device shell pidof $AppPackage 2>$null
    
    if ($pid) {
        Write-Info "App PID: $pid"
        # Filter by PID for cleaner output
        adb -s $device logcat --pid=$pid -v time
    } else {
        Write-Warn "App not running. Showing all logs with package filter..."
        # Fallback: filter by tag patterns
        adb -s $device logcat -v time | Select-String -Pattern "($AppPackage|MangaAutoScroller|ScrollerOverlay|BubbleDetector|ModelManager|ScrollerAccessibility)"
    }
}

# View filtered/important logs
function Show-FilteredLog {
    if (-not (Test-Adb)) { return }
    
    $device = Get-ConnectedDevice
    if (-not $device) { return }
    
    Write-Info "Showing filtered logs (errors, warnings, ML detection) - Press Ctrl+C to stop"
    Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor DarkGray
    
    # Clear logcat first
    adb -s $device logcat -c
    
    Write-Info "Log cleared. Waiting for new entries..."
    Write-Host ""
    
    # Filter for our specific tags and important messages
    $filterTags = @(
        "ScrollerOverlayService:*",
        "BubbleDetector:*",
        "ModelManager:*",
        "ScrollerAccessibilityService:*",
        "MangaAutoScroller:*",
        "PanelDetector:*",
        "*:E"  # All errors
    )
    
    $filterString = $filterTags -join " "
    adb -s $device logcat $filterTags "*:S" -v time
}

# Main execution
switch ($Command) {
    "build"     { Invoke-Build }
    "run"       { Invoke-Run }
    "clean"     { Invoke-Clean }
    "devices"   { Show-Devices }
    "logcat"    { Show-Logcat }
    "log"       { Show-FilteredLog }
    "install"   { Invoke-Install }
    "uninstall" { Invoke-Uninstall }
    "release"   { Invoke-ReleaseBuild }
    "help"      { Show-Help }
    default     { Show-Help }
}
