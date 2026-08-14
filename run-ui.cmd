@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
for /R src\main %%f in (*.java) do (
    set "source=%%f"
    echo "!source:\=/!">>out\sources.txt
)
for /R src\ui %%f in (*.java) do (
    set "source=%%f"
    echo "!source:\=/!">>out\sources.txt
)
javac --release 21 -encoding UTF-8 -d out @out\sources.txt
if errorlevel 1 exit /b %errorlevel%
java -Dfile.encoding=UTF-8 -cp out SimulatorUiMain %*
exit /b %errorlevel%
