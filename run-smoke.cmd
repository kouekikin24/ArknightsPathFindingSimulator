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
javac --release 21 -encoding UTF-8 -cp out -d smoke smoke\SmokeMain.java
if errorlevel 1 exit /b %errorlevel%
echo 注意：冒烟测试会打开真实窗口并接管鼠标键盘约 20 秒，期间请勿操作电脑。
java -Dfile.encoding=UTF-8 -cp out;smoke SmokeMain
exit /b %errorlevel%
