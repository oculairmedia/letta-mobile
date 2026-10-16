@echo off
rem letta-mobile-jvctq: runs the installed meridian CLI (build it once: android-compose\gradlew.bat --no-daemon :cli:installDist).
setlocal
set "MERIDIAN_BIN=%~dp0build\install\meridian\bin\meridian.bat"
if not exist "%MERIDIAN_BIN%" (
  echo meridian is not installed: run android-compose\gradlew.bat --no-daemon :cli:installDist 1>&2
  exit /b 1
)
call "%MERIDIAN_BIN%" %*
exit /b %ERRORLEVEL%
