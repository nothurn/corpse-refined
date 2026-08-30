@echo off
setlocal

set "JAVA_HOME=%~dp0..\_RECOVERY\tools\jdk-21.0.12+8"
set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo.
    echo ERROR: The PlayerCorpse JDK was not found:
    echo %JAVA_HOME%
    echo.
    pause
    exit /b 1
)

:menu
cls
echo ========================================================================
echo CRAFTBOUND DISCORD RELEASE
echo ========================================================================
echo.
echo 1  Configure encrypted Discord webhooks ^(one time^)
echo 2  Validate build and preview without sending
echo 3  Build and publish JAR plus changelog
echo 4  Exit
echo.
choice /c 1234 /n /m "Selection: "

if errorlevel 4 exit /b 0
if errorlevel 3 goto publish
if errorlevel 2 goto validate
if errorlevel 1 goto configure

:configure
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Configure-DiscordRelease.ps1" -ProjectDirectory "%~dp0."
goto result

:validate
call "%~dp0gradlew.bat" validateDiscordRelease --no-daemon --no-configuration-cache
goto result

:publish
echo.
echo This will publish the current version to the two configured Discord channels.
set /p "CONFIRM=Type PUBLISH to continue: "
if /i not "%CONFIRM%"=="PUBLISH" (
    echo Cancelled. Nothing was sent.
    goto result
)
call "%~dp0gradlew.bat" publishDiscordRelease --no-daemon --no-configuration-cache

:result
echo.
pause
goto menu
