@echo off
setlocal EnableDelayedExpansion

:: MangaAutoScroller - Android Development Helper
:: Usage: run.bat [command]

set "APP_PACKAGE=com.zynt.mangaautoscroller"
set "MAIN_ACTIVITY=%APP_PACKAGE%.MainActivity"
set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"

if "%1"=="" goto help
if "%1"=="help" goto help
if "%1"=="build" goto build
if "%1"=="run" goto run
if "%1"=="clean" goto clean
if "%1"=="devices" goto devices
if "%1"=="logcat" goto logcat
if "%1"=="log" goto log
if "%1"=="install" goto install
if "%1"=="uninstall" goto uninstall
if "%1"=="release" goto release

echo Unknown command: %1
goto help

:help
echo.
echo ================================================================
echo        MangaAutoScroller - Development Helper
echo ================================================================
echo.
echo USAGE: run.bat [command]
echo.
echo COMMANDS:
echo   build      Build debug APK
echo   run        Build, install, and launch app
echo   install    Install existing APK to device
echo   uninstall  Uninstall app from device
echo   clean      Clean build files
echo   devices    List connected Android devices
echo   logcat     View ALL app logs (live)
echo   log        View filtered logs (app-specific)
echo   release    Build release APK
echo   help       Show this help message
echo.
echo EXAMPLES:
echo   run.bat run         Quick build and run
echo   run.bat logcat      Watch all logs
echo   run.bat devices     Check connected devices
echo.
goto end

:build
echo [INFO] Building debug APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] Build completed!
    echo [INFO] APK: %APK_PATH%
) else (
    echo [ERROR] Build failed!
)
goto end

:release
echo [INFO] Building release APK...
call gradlew.bat assembleRelease
if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] Release build completed!
    echo [INFO] APK: app\build\outputs\apk\release\app-release.apk
) else (
    echo [ERROR] Release build failed!
)
goto end

:clean
echo [INFO] Cleaning project...
call gradlew.bat clean
if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] Project cleaned!
) else (
    echo [ERROR] Clean failed!
)
goto end

:devices
echo [INFO] Connected Android devices:
echo.
adb devices -l
echo.
:: Get device info
for /f "tokens=1" %%a in ('adb devices ^| findstr /r "device$"') do (
    echo Device: %%a
    for /f "tokens=*" %%m in ('adb -s %%a shell getprop ro.product.model 2^>nul') do echo Model: %%m
    for /f "tokens=*" %%v in ('adb -s %%a shell getprop ro.build.version.release 2^>nul') do echo Android: %%v
)
goto end

:install
if not exist "%APK_PATH%" (
    echo [ERROR] APK not found at %APK_PATH%
    echo [INFO] Run 'run.bat build' first
    goto end
)
echo [INFO] Installing APK...
adb install -r %APK_PATH%
if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] App installed!
) else (
    echo [ERROR] Installation failed!
)
goto end

:uninstall
echo [INFO] Uninstalling %APP_PACKAGE%...
adb uninstall %APP_PACKAGE%
goto end

:run
echo [INFO] Building...
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed!
    goto end
)

echo [INFO] Installing...
adb install -r %APK_PATH%
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Installation failed!
    goto end
)

echo [INFO] Launching app...
adb shell am start -n %APP_PACKAGE%/%MAIN_ACTIVITY%
if %ERRORLEVEL% EQU 0 (
    echo [SUCCESS] App launched!
    echo.
    echo TIP: Run 'run.bat logcat' in another terminal to see logs
) else (
    echo [ERROR] Failed to launch app
)
goto end

:logcat
echo [INFO] Showing logs for %APP_PACKAGE% (Press Ctrl+C to stop)
echo ================================================================
:: Try to get PID first
for /f "tokens=*" %%p in ('adb shell pidof %APP_PACKAGE% 2^>nul') do set "APP_PID=%%p"
if defined APP_PID (
    echo [INFO] App PID: %APP_PID%
    adb logcat --pid=%APP_PID% -v time
) else (
    echo [WARN] App not running. Showing filtered logs...
    adb logcat -v time *:S MangaAutoScroller:* ScrollerOverlayService:* BubbleDetector:* ModelManager:* ScrollerAccessibilityService:* PanelDetector:* *:E
)
goto end

:log
echo [INFO] Clearing logcat and showing app-specific logs...
adb logcat -c
echo [INFO] Waiting for new log entries... (Press Ctrl+C to stop)
echo ================================================================
adb logcat -v time *:S ScrollerOverlayService:* BubbleDetector:* ModelManager:* MangaAutoScroller:* ScrollerAccessibilityService:* PanelDetector:* *:E
goto end

:end
endlocal
