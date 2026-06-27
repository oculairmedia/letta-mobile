@echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%..
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java.exe
)

"%JAVA_EXE%" %JAVA_OPTS% %MERIDIAN_APP_SERVER_OPTS% -cp "%APP_HOME%\lib\*" com.letta.mobile.appservercli.Main %*
exit /b %ERRORLEVEL%
