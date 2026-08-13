@echo off
setlocal
if exist out rmdir /s /q out
mkdir out
for /R src\main %%f in (*.java) do echo %%f>>out\sources.txt
for /R src\ui %%f in (*.java) do echo %%f>>out\sources.txt
javac --release 21 -d out @out\sources.txt
if errorlevel 1 exit /b %errorlevel%
java -cp out SimulatorUiMain %*
