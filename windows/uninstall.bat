@echo off
rem Removes WLH Glass. Google TV's own home screen was never turned off, so nothing else needs restoring.
cd /d "%~dp0"
set TV=192.168.1.50:5555
set ADB=adb\adb.exe
%ADB% connect %TV%
%ADB% -s %TV% uninstall com.wlh.glass
%ADB% -s %TV% shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
pause
