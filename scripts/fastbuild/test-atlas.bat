@echo off
setlocal EnableExtensions
cd /d "%~dp0..\.."
if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if not defined JAVA_HOME exit /b 2
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MAPSYNCER_REGRESSION_JAR=%CD%\output\mapsyncer-atlas-1.0.4-atlas.1-fabric-26.3.jar"
python scripts\testing\run-regressions.py
exit /b %ERRORLEVEL%
