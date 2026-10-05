@echo off
title UDP Mail Server
echo Dang khoi dong UDP Mail Server...
java -cp target/MailUDP-1.0.0.jar server.MailServerGUI
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ========================================================
    echo [LOI] Khong the khoi dong Server!
    echo Nguyen nhan thuong gap:
    echo 1. Chua cai dat Java 17+ tren may (go 'java -version' de kiem tra).
    echo 2. Chua build file JAR (hay chay 'mvn package' hoac mo bang IntelliJ).
    echo ========================================================
    pause
)
