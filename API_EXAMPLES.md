# API Examples

Quick reference for testing the rate limiter API.

## Setup

Start the services:
```bash
docker-compose up
```

Base URL: `http://localhost:8080`

---

## 1. Health Check

```bash
curl http://localhost:8080/api/health
```

Response:
```json
{
  "status": "UP",
  "timestamp": "1703001234567"
}
```

---

## 2. Standard API Endpoint (100 req/min)

```bash
# Request with user ID
curl -H "X-User-ID: user123" http://localhost:8080/api/data
```

Success response:
```json
{
  "message": "Success!",
  "remaining": 97,
  "data": {
    "timestamp": 1703001234567
  }
}
```

Rate limited response (429):
```json
{
  "error": "Rate limit exceeded",
  "message": "Too many requests. Please try again later.",
  "remaining": 0
}
```

---

## 3. Login Endpoint (10 attempts/min)

```bash
curl -X POST http://localhost:8080/api/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john_doe",
    "password": "secret123"
  }'
```

Response:
```json
{
  "message": "Login successful",
  "remaining_attempts": 7
}
```

---

## 4. Batch Processing (Token Bucket)

Process 20 items in a batch:
```bash
curl -X POST http://localhost:8080/api/batch \
  -H "X-User-ID: batch_user" \
  -H "Content-Type: application/json" \
  -d '{
    "size": 20,
    "items": ["item1", "item2", "..."]
  }'
```

Response:
```json
{
  "message": "Batch processed",
  "items_processed": 20,
  "tokens_remaining": 30
}
```

---

## 5. Admin - Reset Rate Limit

```bash
curl -X DELETE http://localhost:8080/admin/reset/user123
```

Response:
```json
{
  "message": "Rate limits reset for user: user123"
}
```

---

## Load Testing Examples

### Using Apache Bench

Test with 1000 requests, 10 concurrent:
```bash
ab -n 1000 -c 10 \
   -H "X-User-ID: loadtest" \
   http://localhost:8080/api/data
```

### Using curl loop

Fire 50 rapid requests:
```bash
for i in {1..50}; do
  curl -s -H "X-User-ID: rapid_user" \
    http://localhost:8080/api/data \
    | jq '.remaining' &
done
wait
```

### Using wrk (if installed)

```bash
wrk -t4 -c100 -d30s \
  -H "X-User-ID: wrk_test" \
  http://localhost:8080/api/data
```

---

## Monitoring Metrics

Get Prometheus metrics:
```bash
curl http://localhost:8080/actuator/metrics/ratelimiter.requests.allowed
curl http://localhost:8080/actuator/metrics/ratelimiter.requests.rejected
curl http://localhost:8080/actuator/metrics/ratelimiter.cache.hits
```

---

## Testing Different Scenarios

### Scenario 1: Burst Traffic

Simulate a burst of traffic from one user:
```bash
#!/bin/bash
for i in {1..150}; do
  status=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-User-ID: burst_user" \
    http://localhost:8080/api/data)
  echo "Request $i: HTTP $status"
  sleep 0.01
done
```

### Scenario 2: Multiple Users

Simulate different users accessing independently:
```bash
for user in alice bob charlie; do
  curl -H "X-User-ID: $user" http://localhost:8080/api/data &
done
wait
```

### Scenario 3: Login Brute Force

Simulate a brute force attack:
```bash
for i in {1..20}; do
  curl -X POST http://localhost:8080/api/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"victim\",\"password\":\"attempt$i\"}"
  sleep 0.1
done
```

---

## Response Headers

The API includes rate limit information in headers (can be enabled):

```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 73
X-RateLimit-Reset: 1703001290
```

---

## Error Codes

| Code | Meaning | Action |
|------|---------|--------|
| 200 | Success | Request processed |
| 429 | Too Many Requests | Wait and retry |
| 500 | Internal Error | Contact support |
