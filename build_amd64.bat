@echo off
setlocal EnableExtensions

set "SERVER_DIR=%~dp0"
if "%SERVER_DIR:~-1%"=="\" set "SERVER_DIR=%SERVER_DIR:~0,-1%"
for %%I in ("%SERVER_DIR%\..") do set "PROJECT_ROOT=%%~fI"
set "DATA_SERVER_DIR=%PROJECT_ROOT%\data_server"
if not defined PACKAGE_DIR set "PACKAGE_DIR=%PROJECT_ROOT%\server_packaged"

where go >nul 2>nul
if errorlevel 1 (
  echo Go compiler not found. Install it first.
  exit /b 1
)

if not exist "%DATA_SERVER_DIR%" (
  echo data_server directory not found: %DATA_SERVER_DIR%
  exit /b 1
)

if not defined GOOS set "GOOS=linux"
if not defined GOARCH set "GOARCH=amd64"
if not defined GOPROXY set "GOPROXY=https://goproxy.cn,direct"
if not defined TIDY set "TIDY=0"

set "BIN_EXT="
if /I "%GOOS%"=="windows" set "BIN_EXT=.exe"

if not defined BIN_NAME set "BIN_NAME=server_%GOOS%_%GOARCH%%BIN_EXT%"
if not defined DATA_BIN_NAME set "DATA_BIN_NAME=data_server_%GOOS%_%GOARCH%%BIN_EXT%"

if not exist "%PACKAGE_DIR%" mkdir "%PACKAGE_DIR%"

echo Target: %GOOS%/%GOARCH%
echo Package output dir: %PACKAGE_DIR%

pushd "%SERVER_DIR%"
if "%TIDY%"=="1" (
  echo Running go mod tidy ^(main^)...
  call go mod tidy
  if errorlevel 1 (
    popd
    exit /b 1
  )
)

echo Compiling main server...
set "GOPROXY=%GOPROXY%"
set "GOOS=%GOOS%"
set "GOARCH=%GOARCH%"
call go build -mod=mod -v -o "%SERVER_DIR%\%BIN_NAME%" .\cmd\api
if errorlevel 1 (
  popd
  exit /b 1
)
popd

pushd "%DATA_SERVER_DIR%"
if "%TIDY%"=="1" (
  echo Running go mod tidy ^(data_server^)...
  call go mod tidy
  if errorlevel 1 (
    popd
    exit /b 1
  )
)

echo Compiling data server...
set "GOPROXY=%GOPROXY%"
set "GOOS=%GOOS%"
set "GOARCH=%GOARCH%"
call go build -mod=mod -v -o "%DATA_SERVER_DIR%\%DATA_BIN_NAME%" .
if errorlevel 1 (
  popd
  exit /b 1
)
popd

if /I "%GOOS%"=="windows" if /I "%GOARCH%"=="amd64" (
  copy /y "%SERVER_DIR%\%BIN_NAME%" "%SERVER_DIR%\server.exe" >nul
  copy /y "%DATA_SERVER_DIR%\%DATA_BIN_NAME%" "%DATA_SERVER_DIR%\data_server.exe" >nul
)

copy /y "%SERVER_DIR%\%BIN_NAME%" "%PACKAGE_DIR%\%BIN_NAME%" >nul
copy /y "%DATA_SERVER_DIR%\%DATA_BIN_NAME%" "%PACKAGE_DIR%\%DATA_BIN_NAME%" >nul

echo Built main server: %SERVER_DIR%\%BIN_NAME%
echo Built data server: %DATA_SERVER_DIR%\%DATA_BIN_NAME%
echo Packaged artifacts:
echo   %PACKAGE_DIR%\%BIN_NAME%
echo   %PACKAGE_DIR%\%DATA_BIN_NAME%

endlocal
exit /b 0
