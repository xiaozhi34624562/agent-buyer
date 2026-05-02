#!/bin/bash
# Start Agent Buyer Console development environment
# Checks MySQL/Redis connectivity, starts backend if needed, launches frontend

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=== Agent Buyer Console Dev Startup ==="

# Check MySQL connectivity
echo "Checking MySQL..."
if mysql -h127.0.0.1 -P3307 -uroot -p'Qaz1234!' -e "SELECT 1" > /dev/null 2>&1; then
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
    cd ../
    MYSQL_PASSWORD='Qaz1234!' SERVER_PORT=8080 mvn spring-boot:run -q &
    cd admin-web
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
npm run dev

echo "${GREEN}Console ready!${NC}"
echo "Access: http://localhost:5173"