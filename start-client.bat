@echo off
cd /d "%~dp0"
echo ============================================
echo   NAGUMO AI - Minecraft Client (verity-client)
echo ============================================
echo.
echo In the game, connect via: Multiplayer -^> Direct Connection -^> localhost
echo (NOT Singleplayer - the voice pipeline only works through a real server.)
echo.
call gradlew.bat :verity-client:runClient
echo.
echo Client closed.
pause
