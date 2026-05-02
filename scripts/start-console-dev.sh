#!/bin/bash
# Start Agent Buyer Console development environment
# Checks MySQL/Redis connectivity, starts backend if needed, launches frontend

set -e

# Resolve script directory to make path-independent
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ADMIN_WEB="$PROJECT_ROOT/admin-web"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=== Agent Buyer Console Dev Startup ==="
echo "Project root: $PROJECT_ROOT"
echo "Admin-web: $ADMIN_WEB"

# Get MySQL password from environment or prompt
if [ -z "$MYSQL_PASSWORD" ]; then
    echo "${YELLOW}MYSQL_PASSWORD not set. Using default for local dev.${NC}"
    MYSQL_PASSWORD='Qaz1234!'
fi

# Check MySQL connectivity
echo "Checking MySQL..."
if mysql -h127.0.0.1 -P3307 -uroot -p"$MYSQL_PASSWORD" -e "SELECT 1" > /dev/null 2>&1; then
    echo "${GREEN}MySQL OK${NC}"
else
    echo "${RED}MySQL NOT OK - please start: docker start agent-buyer-mysql8${NC}"
    exit 1
fi

# Check Redis connectivity
echo "Checking Redis..."
if redis-cli -h 127.0.0.1 -p 6380 ping > /dev/null 2>&1; then
    echo "${GREEN}Redis OK${NC}"
else
    echo "${RED}Redis NOT OK - please start: docker start agent-buyer-redis7${NC}"
    exit 1
fi

# Check if backend already running on 8080
echo "Checking backend..."
if lsof -i :8080 > /dev/null 2>&1; then
    echo "${YELLOW}Backend already running on 8080, skipping startup${NC}"
else
    echo "Starting backend on 8080..."
    cd "$PROJECT_ROOT"
    MYSQL_PASSWORD="$MYSQL_PASSWORD" SERVER_PORT=8080 mvn spring-boot:run -q &
    cd "$ADMIN_WEB"
    # Wait for backend to start
    echo "Waiting for backend..."
    for i in {1..30}; do
        if curl -s http://127.0.0.1:8080/actuator/health > /dev/null 2>&1; then
            echo "${GREEN}Backend started${NC}"
            break
        fi
        sleep 1
    done
fi

# Start frontend
echo "Starting frontend..."
cd "$ADMIN_WEB"
npm run dev

echo "${GREEN}Console ready!${NC}"
echo "Access: http://localhost:5173"