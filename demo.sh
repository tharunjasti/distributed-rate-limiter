#!/bin/bash

# Demo script for distributed rate limiter
# Shows different rate limiting scenarios

set -e

BASE_URL="http://localhost:8080"
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "=========================================="
echo "  Distributed Rate Limiter Demo"
echo "=========================================="
echo ""

# Check if server is running
echo "Checking if server is running..."
if ! curl -sf "$BASE_URL/api/health" > /dev/null; then
    echo -e "${RED}Error: Server not running at $BASE_URL${NC}"
    echo "Start with: docker-compose up"
    exit 1
fi

echo -e "${GREEN}✓ Server is running${NC}"
echo ""

# Demo 1: Standard API rate limiting
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Demo 1: Standard API (100 req/min)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Making 5 requests as user_demo..."

for i in {1..5}; do
    response=$(curl -s -H "X-User-ID: user_demo" "$BASE_URL/api/data")
    remaining=$(echo $response | grep -o '"remaining":[0-9]*' | cut -d':' -f2)
    echo -e "  Request $i: ${GREEN}✓ Success${NC} - $remaining permits remaining"
    sleep 0.1
done

echo ""

# Demo 2: Rate limit exceeded
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Demo 2: Exceeding Rate Limit"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Rapid-firing 15 requests to hit limit..."

success=0
rejected=0

for i in {1..15}; do
    status=$(curl -s -o /dev/null -w "%{http_code}" -H "X-User-ID: user_rapid" "$BASE_URL/api/data")
    if [ "$status" = "200" ]; then
        ((success++))
        echo -e "  Request $i: ${GREEN}✓ Allowed${NC}"
    else
        ((rejected++))
        echo -e "  Request $i: ${RED}✗ Rate limited (429)${NC}"
    fi
    sleep 0.01
done

echo ""
echo "Summary: $success allowed, $rejected rejected"
echo ""

# Demo 3: Login rate limiting (stricter)
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Demo 3: Login Rate Limiting (10/min)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Simulating brute force attack..."

for i in {1..12}; do
    status=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/login" \
        -H "Content-Type: application/json" \
        -d '{"username":"test_user","password":"wrong"}')
    
    if [ "$status" = "200" ]; then
        echo -e "  Attempt $i: ${GREEN}✓ Allowed${NC}"
    else
        echo -e "  Attempt $i: ${RED}✗ Blocked (429)${NC}"
    fi
    sleep 0.05
done

echo ""

# Demo 4: Batch processing
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Demo 4: Batch Processing (Token Bucket)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

batches=(5 10 20 30)
for size in "${batches[@]}"; do
    response=$(curl -s -X POST "$BASE_URL/api/batch" \
        -H "X-User-ID: batch_user" \
        -H "Content-Type: application/json" \
        -d "{\"size\":$size}")
    
    if echo "$response" | grep -q "Batch processed"; then
        tokens=$(echo $response | grep -o '"tokens_remaining":[0-9]*' | cut -d':' -f2)
        echo -e "  Batch size $size: ${GREEN}✓ Processed${NC} - $tokens tokens remaining"
    else
        echo -e "  Batch size $size: ${RED}✗ Rejected${NC}"
    fi
    sleep 0.2
done

echo ""

# Demo 5: Multiple users
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Demo 5: Multiple Users (Isolation)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

users=("alice" "bob" "charlie")
for user in "${users[@]}"; do
    response=$(curl -s -H "X-User-ID: $user" "$BASE_URL/api/data")
    remaining=$(echo $response | grep -o '"remaining":[0-9]*' | cut -d':' -f2)
    echo -e "  User '$user': ${GREEN}✓ Success${NC} - $remaining permits remaining"
done

echo ""

# Demo 6: Reset rate limit
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Demo 6: Admin Reset"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

echo "Resetting limit for user 'user_demo'..."
curl -s -X DELETE "$BASE_URL/admin/reset/user_demo" | grep -o '"message":"[^"]*"' | cut -d'"' -f4
echo ""

echo "Verifying reset..."
response=$(curl -s -H "X-User-ID: user_demo" "$BASE_URL/api/data")
remaining=$(echo $response | grep -o '"remaining":[0-9]*' | cut -d':' -f2)
echo -e "${GREEN}✓ Limit reset successfully${NC} - $remaining permits available"

echo ""
echo "=========================================="
echo "  Demo Complete!"
echo "=========================================="
echo ""
echo "Try these commands yourself:"
echo "  curl -H 'X-User-ID: myuser' $BASE_URL/api/data"
echo "  curl -X POST -H 'Content-Type: application/json' \\"
echo "    -d '{\"username\":\"test\"}' $BASE_URL/api/login"
echo ""
