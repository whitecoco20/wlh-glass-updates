@echo off
rem Copies every .jpg/.png in the "backgrounds" folder next to this file onto the TV.
rem Then press the Background button in WLH Glass to flip through them.
cd /d "%~dp0"
set TV=192.168.1.50:5555
set ADB=adb\adb.exe
%ADB% connect %TV%
for %%f in (backgrounds\*.jpg backgrounds\*.jpeg backgrounds\*.png backgrounds\*.webp) do %ADB% -s %TV% push "%%f" /sdcard/Android/data/com.wlh.glass/files/backgrounds/
pause
