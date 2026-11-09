@echo off
setlocal EnableExtensions
cd /d "%~dp0..\.."
if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if not defined JAVA_HOME (
    echo Set JAVA_HOME to a Java 25 JDK and run this script again.
    exit /b 2
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist cache\tmp\atlas mkdir cache\tmp\atlas
set "TEMP=%CD%\cache\tmp\atlas"
set "TMP=%TEMP%"
set "TMPDIR=%TEMP%"
if exist settings.atlas-backup.gradle (
    echo Restore settings.atlas-backup.gradle before rebuilding after an interrupted build.
    exit /b 3
)
copy settings.gradle settings.atlas-backup.gradle >nul
copy scripts\fastbuild\settings-atlas.gradle settings.gradle >nul
call gradlew.bat :mc-26.3:fabric:build -PatlasBuild --project-cache-dir cache/gradle-project-cache %GRADLE_OFFLINE%
set "RESULT=%ERRORLEVEL%"
move /y settings.atlas-backup.gradle settings.gradle >nul
if not "%RESULT%"=="0" exit /b %RESULT%
call scripts\fastbuild\copy-release-jars.bat mc-26.3\fabric\build\libs
exit /b %ERRORLEVEL%
