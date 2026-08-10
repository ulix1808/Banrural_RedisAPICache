# Banrural — Cache Redis (ejemplo mínimo)

Ejemplo enfocado en **un solo catálogo**: instituciones financieras del endpoint `/1068`.  
Patrón **Cache-Aside** con **Jedis 7.5.3** (`RedisClient` API).

## Qué hace

```
GET /instituciones → Redis HIT  → respuesta inmediata
                   → Redis MISS → API HTTP → guarda en Redis (TTL) → respuesta
```

## Cómo funciona (Cache-Aside)

La lógica central está en `InstitucionesFinancierasService.obtenerInstituciones()`.  
Cada vez que llega una petición, el servicio sigue estos 3 pasos:

```
1. Buscar en Redis
        │
        ├── HIT (dato existe) → devolver JSON cacheado (fromCache: true)
        │
        └── MISS (no existe) ↓

2. Llamar al API de instituciones financieras (HTTP GET)

3. Guardar la respuesta en Redis con TTL → devolver JSON (fromCache: false)
```

Código equivalente:

```java
public CachedApiResult obtenerInstituciones() {
    // Paso 1: Intentar leer de Redis
    String cached = cacheService.get(CACHE_KEY);
    if (cached != null) {
        return CachedApiResult.fromCache(cached, CACHE_KEY, ttl);
    }

    // Paso 2: Cache miss → llamar al API downstream
    String apiResponse = apiClient.consultarInstituciones();

    // Paso 3: Guardar en Redis con TTL
    cacheService.put(CACHE_KEY, apiResponse, ttl);

    return CachedApiResult.fromApi(apiResponse, CACHE_KEY);
}
```

| Paso | Qué pasa | `fromCache` | Se llama al API |
|------|----------|-------------|-----------------|
| 1ª petición | MISS → API → guarda en Redis | `false` | Sí |
| 2ª petición (antes del TTL) | HIT → lee de Redis | `true` | No |
| Tras `DELETE /cache/...` | MISS otra vez | `false` | Sí |

Las operaciones directas contra Redis (`get`, `setex`, `del`, `ttl`) están en `RedisCacheService`, que usa Jedis `RedisClient`.

## Diseño de la KEY (llave de cache)

### ¿Qué se usa como llave?

La llave completa en Redis se arma en dos partes:

```
[prefijo configurado] + [identificador lógico del dato]
```

En este ejemplo:

| Parte | Valor | Origen |
|-------|-------|--------|
| Prefijo | `banrural:transferencias:` | `application.yml` → `banrural.cache.key-prefix` |
| Identificador | `catalogo:instituciones-financieras` | Constante `CACHE_KEY` en el servicio |

**Llave final en Redis:**

```
banrural:transferencias:catalogo:instituciones-financieras
```

El prefijo evita colisiones con otras apps o módulos que usen el mismo Redis.  
El identificador describe **qué dato** es (catálogo, usuario, recurso).

### ¿Por qué esta llave es fija?

El API de instituciones financieras es un **catálogo global**:

- No recibe `usuario`, `oficina` ni `canal`
- Siempre devuelve la misma lista para todos
- La respuesta **no cambia** según quién consulta

Por eso una sola llave alcanza: todos los usuarios comparten el mismo JSON en Redis.  
Eso maximiza el beneficio del cache (un MISS sirve a miles de peticiones posteriores).

### ¿Qué pasa si el API tiene parámetros?

Si la respuesta **depende de los parámetros**, la llave **debe incluir esos parámetros**.  
Cada combinación distinta de parámetros = una entrada distinta en Redis.

```
Sin parámetros (catálogo global)     → 1 llave para todos
Con parámetros (dato por usuario)    → 1 llave por cada combinación de params
```

**Regla:** la llave identifica **exactamente** la respuesta que guardaste.  
Si dos peticiones pueden devolver JSON distinto, **no pueden usar la misma llave**.

#### Ejemplo: APIs del `/1068` con parámetros

En la pantalla de transferencias, otros servicios reciben `usuario` y la respuesta varía:

| API | Parámetros | Llave propuesta |
|-----|------------|-----------------|
| Instituciones financieras | ninguno (catálogo) | `catalogo:instituciones-financieras` |
| Top cuentas destino | `usuario` | `usuario:USR001:top-cuentas` |
| Cuentas propias | `usuario` | `usuario:USR001:cuentas-propias` |
| Cuentas terceros | `usuario` | `usuario:USR001:cuentas-terceros` |

Llaves completas en Redis:

```
banrural:transferencias:catalogo:instituciones-financieras      ← todos comparten
banrural:transferencias:usuario:USR001:top-cuentas               ← solo USR001
banrural:transferencias:usuario:USR002:top-cuentas               ← solo USR002
```

#### Código equivalente para un API con parámetros

```java
public CachedApiResult obtenerTopCuentas(String usuario) {
    String cacheKey = "usuario:" + usuario + ":top-cuentas";

    String cached = cacheService.get(cacheKey);
    if (cached != null) {
        return CachedApiResult.fromCache(cached, cacheKey, cacheService.getTtlRemaining(cacheKey));
    }

    String apiResponse = apiClient.consultarTopCuentas(usuario);
    cacheService.put(cacheKey, apiResponse, userDataTtlSeconds);

    return CachedApiResult.fromApi(apiResponse, cacheKey);
}
```

#### ¿Qué pasa si usas la misma llave con params distintos?

**Error de diseño:** si cacheas con llave fija pero el API recibe parámetros:

```
Petición usuario=USR001 → guardas en "top-cuentas"
Petición usuario=USR002 → HIT en "top-cuentas" → devuelve datos de USR001 ❌
```

Siempre incluir en la llave todo lo que hace variar la respuesta (`usuario`, `oficina`, `canal`, filtros, etc.).

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

Este ejemplo cubre el **catálogo global** (API 1 de 5) con llave fija.  
Los otros 4 APIs del `/1068` varían por `usuario` — ver sección [Diseño de la KEY](#diseño-de-la-key-llave-de-cache).
