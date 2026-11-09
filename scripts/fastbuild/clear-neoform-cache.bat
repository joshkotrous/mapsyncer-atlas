@echo off
chcp 65001 >nul
setlocal EnableExtensions

for %%I in ("%~dp0..\..") do set "PROJECT_ROOT=%%~sI"
set "GRADLE_CACHE=%USERPROFILE%\.gradle\caches"

echo Clearing the NeoForm cache...

for %%D in (
    "%GRADLE_CACHE%\modules-2\files-2.1\net.neoforged.neoform"
    "%GRADLE_CACHE%\neoform"
    "%GRADLE_CACHE%\neoforged"
    "%GRADLE_CACHE%\transforms-4"
    "%GRADLE_CACHE%\8.9"
    "%PROJECT_ROOT%\mc-1.21.11\neoforge\build"
    "%PROJECT_ROOT%\mc-1.21.1\neoforge\build"
) do (
    if exist "%%~D" (
        rd /s /q "%%~D"
        echo Cleared: %%~D
    )
)

echo.
echo Cache cleanup complete.
echo You can now rebuild NeoForge 1.21.11:
echo   gradlew.bat :mc-1.21.11:neoforge:build -x test --no-daemon --refresh-dependencies
exit /b 0
