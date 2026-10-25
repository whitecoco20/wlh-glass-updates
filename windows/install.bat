@echo off
rem Installs or updates WLH Glass from this PC.
rem 1) Put Google's platform-tools next to this file so that adb\adb.exe exists
rem    (https://developer.android.com/tools/releases/platform-tools - rename the folder to "adb").
rem 2) Put the APK (from the releases folder) next to this file.
rem 3) Change TV= below to your TV's IP (TV Settings > Network & Internet).
cd /d "%~dp0"
set TV=192.168.1.50:5555
set ADB=adb\adb.exe
if not exist %ADB% (echo adb\adb.exe not found. See the notes at the top of this file. & pause & exit /b 1)
echo Turn on USB debugging on the TV. Press Allow on the TV if it asks.
%ADB% connect %TV%
timeout /t 8 /nobreak > nul
%ADB% connect %TV%
for %%f in (wlh-glass-*.apk) do set APK=%%f
if "%APK%"=="" (echo No wlh-glass-*.apk next to this file. & pause & exit /b 1)
%ADB% -s %TV% install -r %APK%
%ADB% -s %TV% shell am start -n com.wlh.glass/.HomeActivity
%ADB% -s %TV% shell settings get secure enabled_accessibility_services
echo.
echo Next, on the TV: Settings ^> System ^> Accessibility ^> WLH Glass Home button ^> On
echo Then turn USB debugging off again.
pause
