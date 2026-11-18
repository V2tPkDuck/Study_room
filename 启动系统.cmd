@echo off
setlocal
cd /d "%~dp0"
title Study Room Demo

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"
set "JAR=app\study-room.jar"

rem If 8080 is already used and no port was passed, try 8081.
if "%~1"=="" (
  powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',8080);$c.Close();exit 0}catch{exit 1}" >nul 2>nul
  if not errorlevel 1 (
    echo Port 8080 is busy, using 8081 instead.
    set "PORT=8081"
  )
)

if not exist "%JAR%" (
  echo [ERROR] "%JAR%" not found.
  echo Put this file in the same folder as the "app" folder.
  pause
  exit /b 1
)

rem Use java from PATH first, then JAVA_HOME and common JDK folders.
set "JAVA_EXE="
where java >nul 2>nul
if not errorlevel 1 set "JAVA_EXE=java"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\Java\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%ProgramFiles%\Microsoft\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
if not defined JAVA_EXE for /d %%D in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"

if not defined JAVA_EXE (
  echo [ERROR] Java was not found.
  echo Install JDK 17 or newer, then double-click this file again.
  echo Download: https://adoptium.net/temurin/releases/
  pause
  exit /b 1
)

echo.
echo ============================================================
echo   Study Room Reservation and Management System
echo   Java: %JAVA_EXE%
echo   URL:  http://localhost:%PORT%
echo   First start creates the database and takes 5-10 seconds.
echo   Press Ctrl+C in this window to stop the server.
echo ============================================================
echo.

rem Open the browser after the server has had time to start.
start "" powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 8; Start-Process 'http://localhost:%PORT%'"

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" --server.port=%PORT%
set "CODE=%ERRORLEVEL%"

echo.
if not "%CODE%"=="0" (
  echo [INFO] Server exited with code %CODE%.
  echo If port %PORT% is busy, close the other program or run:
  echo     Run this file from cmd with argument 8081
)
pause
