@echo off
cd /d "%~dp0"
echo ============================================
echo   NAGUMO AI - Clean and rebuild
echo ============================================
echo.
echo This is safe: it only removes generated build output (build/ folders).
echo It does NOT touch your world save, logs, or settings in the run/ folders.
echo.
echo Stopping any running Gradle daemons...
call gradlew.bat --stop
echo.
echo Cleaning build output...
call gradlew.bat clean
echo.
echo Done. Now try start-server.bat and start-client.bat again.
pause
