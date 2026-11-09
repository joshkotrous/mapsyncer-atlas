@echo off
setlocal EnableExtensions
cd /d "%~dp0..\.."
if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if not defined JAVA_HOME exit /b 2
set "PATH=%JAVA_HOME%\bin;%PATH%"
for /f "tokens=1,* delims==" %%A in (gradle.properties) do if "%%A"=="mod_version" set "ATLAS_VERSION=%%B"
set "MAPSYNCER_REGRESSION_JAR=%CD%\output\mapsyncer-atlas-%ATLAS_VERSION%-fabric-26.3.jar"
python scripts\testing\check-english-release.py --jar "%MAPSYNCER_REGRESSION_JAR%"
if errorlevel 1 exit /b %ERRORLEVEL%
python scripts\testing\run-regressions.py
exit /b %ERRORLEVEL%
