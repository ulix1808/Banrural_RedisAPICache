#!/bin/bash
# Prueba HIT / MISS / EVICT del catálogo de instituciones

BASE_URL="http://localhost:8080/api/v1"
REDIS_HOST="${REDIS_HOST:-10.160.208.165}"

echo "1) Primera llamada (CACHE MISS)"
curl -s "$BASE_URL/instituciones-financieras" | python3 -m json.tool
echo ""

echo "2) Segunda llamada (CACHE HIT)"
curl -s "$BASE_URL/instituciones-financieras" | python3 -m json.tool
echo ""

echo "3) Invalidar cache"
curl -s -X DELETE "$BASE_URL/cache/instituciones-financieras" | python3 -m json.tool
echo ""

echo "4) Tercera llamada (CACHE MISS de nuevo)"
curl -s "$BASE_URL/instituciones-financieras" | python3 -m json.tool
echo ""

echo "5) Key en Redis"
redis-cli -h "$REDIS_HOST" -p 6379 GET "banrural:transferencias:catalogo:instituciones-financieras"
redis-cli -h "$REDIS_HOST" -p 6379 TTL "banrural:transferencias:catalogo:instituciones-financieras"
