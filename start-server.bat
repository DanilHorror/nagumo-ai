@echo off
cd /d "%~dp0"
echo ============================================
echo   NAGUMO AI - Minecraft Server (verity-server)
echo ============================================
echo.
echo Before running this, make sure these are already running:
echo   - gigaam_server.py   (STT, port 5002)
echo   - piper http_server  (TTS, port 5001)
echo Otherwise the voice pipeline will not work.
echo.
call gradlew.bat :verity-server:runServer
echo.
echo Server stopped.
pause
