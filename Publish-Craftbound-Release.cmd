@echo off
setlocal
title Craftbound Release

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

if not exist "%~dp0.discord-release-secrets.json" (
    echo.
    echo ERROR: Discord webhooks are not configured yet.
    echo Run Discord-Release.cmd and choose option 1 first.
    echo.
    pause
    exit /b 1
)

if not exist "%~dp0.discord-release-key.bin" (
    echo.
    echo ERROR: The Discord release encryption key is missing.
    echo Run Discord-Release.cmd and choose option 1 again.
    echo.
    pause
    exit /b 1
)

set "MOD_VERSION=current"
for /f "tokens=2 delims==" %%V in ('findstr /b /c:"mod_version=" "%~dp0gradle.properties"') do set "MOD_VERSION=%%V"

echo.
echo ========================================================================
echo CRAFTBOUND ONE-CLICK RELEASE - VERSION %MOD_VERSION%
echo ========================================================================
echo.
echo This builds the mod and publishes its JAR and changelog to Discord.
echo Test builds are not published unless you confirm this prompt.
echo Already published versions are protected against duplicates.
echo.
choice /c YN /n /m "Publish this release now? [Y/N]: "
if errorlevel 2 (
    echo.
    echo Cancelled. Nothing was sent.
    echo.
    pause
    exit /b 0
)

echo.
call "%~dp0gradlew.bat" publishDiscordRelease --no-daemon --no-configuration-cache
set "RELEASE_EXIT=%ERRORLEVEL%"

echo.
if not "%RELEASE_EXIT%"=="0" (
    echo RELEASE FAILED. Review the error above; the release state prevents duplicates on retry.
    echo.
    pause
    exit /b %RELEASE_EXIT%
)

echo RELEASE COMPLETE. The JAR and changelog were published to Discord.
echo.
pause
exit /b 0
