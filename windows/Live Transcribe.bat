@echo off
REM Double-click to open the Live Transcribe window (no console).
start "" "%USERPROFILE%\.conda\envs\voicetx\pythonw.exe" "%~dp0live_transcribe.py"
