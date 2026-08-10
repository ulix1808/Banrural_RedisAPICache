# Banrural — Cache Redis (ejemplo mínimo)

Ejemplo enfocado en **un solo catálogo**: instituciones financieras del endpoint `/1068`.  
Patrón **Cache-Aside** con **Jedis 7.5.3** (`RedisClient` API).

## Qué hace

```
GET /instituciones → Redis HIT  → respuesta inmediata
                   → Redis MISS → API HTTP → guarda en Redis (TTL) → respuesta
```

## Estructura

```
src/main/java/com/banrural/cache/
├── config/AppConfig.java          # RedisClient (Jedis) + RestClient
├── controller/CatalogoController.java
├── service/
│   ├── RedisCacheService.java     # get / setex / del / ttl
│   └── InstitucionesFinancierasService.java  # Cache-Aside
├── client/InstitucionesFinancierasClient.java
└── model/CachedApiResult.java
```

## Requisitos

- Java 17+, Maven 3.8+
- Redis accesible (por defecto `10.160.208.165:6379`)
- Red interna para el API de instituciones (o cambiar `INSTITUCIONES_URL`)

## Ejecutar

```bash
# Verificar Redis
redis-cli -h 10.160.208.165 -p 6379 ping

# Compilar y arrancar
mvn clean package -DskipTests
mvn spring-boot:run
```

## Probar

```bash
# MISS (primera vez)
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool

# HIT (segunda vez — fromCache: true)
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool

# Invalidar
curl -s -X DELETE http://localhost:8080/api/v1/cache/instituciones-financieras

# Script completo
bash scripts/probar-cache.sh
```

## Configuración

| Variable | Default | Descripción |
|----------|---------|-------------|
| `REDIS_HOST` | `10.160.208.165` | Host Redis |
| `REDIS_PORT` | `6379` | Puerto Redis |
| `INSTITUCIONES_URL` | URL red interna | API de instituciones |
| `CACHE_CATALOG_TTL` | `3600` | TTL en segundos |

**Key en Redis:** `banrural:transferencias:catalogo:instituciones-financieras`

## Jedis

Cliente directo, sin Spring Data Redis / Lettuce:

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>7.5.3</version>
</dependency>
```

Usa la API moderna `RedisClient` (Jedis ≥ 7.2).

## Redis local (opcional)

Solo si no tienes la instancia corporativa:

```bash
docker compose up -d
export REDIS_HOST=localhost
mvn spring-boot:run
```

## Tests

```bash
mvn test
```

## Contexto /1068

Este ejemplo cubre solo el **catálogo global** (API 1 de 5). Los otros catálogos del `/1068` se pueden extender con el mismo patrón y keys por usuario.
