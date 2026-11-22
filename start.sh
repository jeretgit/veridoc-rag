#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "============================================"
echo "  veridoc-rag — One-Click Start"
echo "============================================"
echo ""

# 1. Ensure .env exists
if [ ! -f .env ]; then
  echo "[INFO] No .env found. Copying from .env.example ..."
  cp .env.example .env
  echo "[INFO] .env created with default values."
else
  echo "[INFO] .env already exists."
fi
echo ""

# 2. Build and start all containers
echo "[INFO] Running: docker compose up -d --build"
echo ""
docker compose up -d --build
echo ""

# 3. Print access URL
source .env 2>/dev/null || true
FRONTEND_PORT="${FRONTEND_PORT:-4200}"

echo "============================================"
echo "  Startup complete!"
echo "  Frontend:  http://localhost:${FRONTEND_PORT}"
echo "  Backend:   http://localhost:${BACKEND_PORT:-8080}/api/health"
echo "============================================"
