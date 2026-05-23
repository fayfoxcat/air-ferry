@echo off
REM =============================================================================
REM setup.bat - CFC project one-click setup script (Windows)
REM
REM Downloads external dependencies into the project root directory.
REM Usage:
REM   setup.bat              all (libcimbar + OpenCV)
REM   setup.bat --libcimbar  libcimbar only
REM   setup.bat --opencv     OpenCV only (for WASM build)
REM =============================================================================
setlocal EnableDelayedExpansion

cd /d "%~dp0"

REM ---- version config ---------------------------------------------------------
set LIBCIMBAR_REPO=https://github.com/sz3/libcimbar.git
set LIBCIMBAR_BRANCH=master

set OPENCV_REPO=https://github.com/opencv/opencv.git
set OPENCV_TAG=4.13.0

REM ---- dispatch ----
if "%~1"=="" goto :both
if "%~1"=="-h" goto :show_help
if "%~1"=="--help" goto :show_help
if "%~1"=="--libcimbar" goto :setup_libcimbar
if "%~1"=="--opencv" goto :setup_opencv

echo Unknown option: %~1
echo Usage: setup.bat [--libcimbar^^|--opencv^^|-h]
exit /b 1

REM ---- help ----
:show_help
echo Usage: setup.bat [--libcimbar^^|--opencv^^|-h]
echo.
echo   (no args)     Download libcimbar + OpenCV to project root
echo   --libcimbar   Download libcimbar only (required for app build)
echo   --opencv      Download OpenCV source only (for WASM encoder build)
echo   -h, --help    Show this help
exit /b 0

REM ---- both ----
:both
call :do_libcimbar
if errorlevel 1 exit /b 1
call :do_opencv
goto :done

REM ---- libcimbar only ----
:setup_libcimbar
call :do_libcimbar
goto :done

REM ---- opencv only ----
:setup_opencv
call :do_opencv
goto :done

REM ---- do_libcimbar -----------------------------------------------------------
:do_libcimbar
echo ==============================================
echo ^>^>^> Initializing libcimbar
echo ==============================================

if exist "libcimbar\" (
    echo [skip] libcimbar/ already exists, skipping clone.
    echo        To re-pull: rmdir /s /q libcimbar\
    exit /b 0
)

echo [clone] %LIBCIMBAR_REPO% (branch: %LIBCIMBAR_BRANCH%)
git clone --depth 1 --branch %LIBCIMBAR_BRANCH% %LIBCIMBAR_REPO% libcimbar
if errorlevel 1 (
    echo [ERROR] Failed to clone libcimbar
    exit /b 1
)
echo [done] libcimbar downloaded to %CD%\libcimbar\
exit /b 0

REM ---- do_opencv --------------------------------------------------------------
:do_opencv
echo ==============================================
echo ^>^>^> Initializing OpenCV
echo ==============================================

if exist "opencv4\" (
    echo [skip] opencv4/ already exists, skipping clone.
    echo        To re-pull: rmdir /s /q opencv4\
    exit /b 0
)

echo [clone] %OPENCV_REPO% (tag: %OPENCV_TAG%)
git clone --depth 1 --branch %OPENCV_TAG% %OPENCV_REPO% opencv4
if errorlevel 1 (
    echo [ERROR] Failed to clone OpenCV
    exit /b 1
)
echo [done] OpenCV source downloaded to %CD%\opencv4\
echo        This directory is only needed for WASM encoder build.
exit /b 0

REM ---- done ----
:done
echo.
echo ==============================================
echo ^>^>^> Setup complete!
echo ==============================================
echo.
echo Next steps:
echo   Android build: Open project root in Android Studio, configure OpenCV SDK path, then build
echo   WASM encoder:  docker run --rm --mount type=bind,source="%%cd%%",target="/usr/src/app" emscripten/emsdk:3.1.69 bash /usr/src/app/web/build-wasm-encode.sh
endlocal
exit /b 0
