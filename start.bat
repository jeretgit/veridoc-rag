@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

echo ============================================
echo   veridoc-rag — One-Click Start
echo ============================================
echo.

REM 1. Ensure .env exists
if not exist .env (
  echo [INFO] No .env found. Copying from .env.example ...
  copy .env.example .env >nul
  echo [INFO] .env created with default values.
) else (
  echo [INFO] .env already exists.
)
echo.

REM 2. Build and start all containers
echo [INFO] Running: docker compose up -d --build
echo.
docker compose up -d --build
if errorlevel 1 (
  echo [ERROR] docker compose failed. Please check the output above.
  exit /b 1
)
echo.

REM 3. Extract FRONTEND_PORT from .env
set FRONTEND_PORT=4200
for /f "tokens=1,2 delims==" %%a in (.env) do (
  if "%%a"=="FRONTEND_PORT" set FRONTEND_PORT=%%b
)
set BACKEND_PORT=8080
for /f "tokens=1,2 delims==" %%a in (.env) do (
  if "%%a"=="BACKEND_PORT" set BACKEND_PORT=%%b
)

echo ============================================
echo   Startup complete!
echo   Frontend:  http://localhost:!FRONTEND_PORT!
echo   Backend:   http://localhost:!BACKEND_PORT!/api/health
echo ============================================
