@echo off
REM Same app, but with a console window so you can see errors while troubleshooting.
"%USERPROFILE%\.conda\envs\voicetx\python.exe" "%~dp0live_transcribe.py"
echo.
pause
