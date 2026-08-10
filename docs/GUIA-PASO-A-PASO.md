# Guía paso a paso — Probar el cache de Redis

Tutorial hands-on para que el equipo pueda levantar el proyecto, probar el cache, y verificar que funciona correctamente.

---

## Pre-requisitos

Antes de empezar, verifica que tienes instalado:

```bash
# Java 17+
java -version
# openjdk version "17.0.x" o superior

# Maven
mvn -version
# Apache Maven 3.8.x o superior

# Docker
docker --version
# Docker version 20.x o superior
```

---

## Paso 1: Clonar / descargar el proyecto

```bash
cd /ruta/de/tu/workspace
# El proyecto ya debe estar en Banrural_RedisAPICache/
cd Banrural_RedisAPICache
```

---

## Paso 2: Levantar Redis con Docker

```bash
docker compose up -d
```

**Verificar:**

```bash
# El contenedor debe estar "healthy"
docker compose ps

# Debe responder PONG
redis-cli ping
```

Si no tienes `redis-cli` instalado localmente, puedes usar Docker:

```bash
docker exec -it banrural-redis redis-cli ping
```

**Resultado esperado:** `PONG`

---

## Paso 3: Compilar el proyecto

```bash
mvn clean package -DskipTests
```

**Resultado esperado:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: ~15s
```

Si hay errores de compilación, verifica que tienes Java 17+ configurado:

```bash
echo $JAVA_HOME
java -version
```

---

## Paso 4: Ejecutar la aplicación (modo local con mock)

Usamos el perfil `local` que apunta a un API mock interno (no necesitas red interna):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**Espera a ver en los logs:**

```
Started TransferenciasApplication in X.XXX seconds
```

La app está corriendo en `http://localhost:8080`.

---

## Paso 5: Verificar que la app está viva

Abre otra terminal y ejecuta:

```bash
curl http://localhost:8080/actuator/health
```

**Resultado esperado:**
```json
{"status":"UP"}
```

---

## Paso 6: Primera llamada — CACHE MISS

Esta es la primera vez que se consulta el catálogo. Redis está vacío, así que la app llamará al API (mock):

```bash
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool
```

**Resultado esperado:**

```json
{
    "data": "{\"codigo\":\"00\",\"mensaje\":\"Consulta exitosa\",\"instituciones\":[...]}",
    "fromCache": false,
    "cacheKey": "catalogo:instituciones-financieras",
    "ttlRemainingSeconds": null
}
```

**Puntos clave:**
- `"fromCache": false` → vino del API, no de Redis
- `"ttlRemainingSeconds": null` → no aplica porque no estaba en cache

**En los logs de la app verás:**
```
CACHE MISS → key=banrural:transferencias:catalogo:instituciones-financieras
Llamando API instituciones financieras → http://localhost:8080/mock/...
CACHE SET  → key=banrural:transferencias:catalogo:instituciones-financieras, ttl=60s
```

---

## Paso 7: Segunda llamada — CACHE HIT

Inmediatamente repite la misma llamada:

```bash
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool
```

**Resultado esperado:**

```json
{
    "data": "{\"codigo\":\"00\",\"mensaje\":\"Consulta exitosa\",\"instituciones\":[...]}",
    "fromCache": true,
    "cacheKey": "catalogo:instituciones-financieras",
    "ttlRemainingSeconds": 58
}
```

**Puntos clave:**
- `"fromCache": true` → vino de Redis, NO se llamó al API
- `"ttlRemainingSeconds": 58` → le quedan 58 segundos de vida al cache
- La respuesta fue casi instantánea

**En los logs:**
```
CACHE HIT  → key=banrural:transferencias:catalogo:instituciones-financieras
Instituciones financieras servidas desde CACHE (ttl=58s)
```

**¡El cache funciona!** La segunda llamada no fue al API.

---

## Paso 8: Verificar directamente en Redis

```bash
# Ver el contenido cacheado
redis-cli GET "banrural:transferencias:catalogo:instituciones-financieras"

# Ver cuántos segundos le quedan
redis-cli TTL "banrural:transferencias:catalogo:instituciones-financieras"

# Ver todas las keys del proyecto
redis-cli KEYS "banrural:transferencias:*"
```

O con Docker:

```bash
docker exec -it banrural-redis redis-cli GET "banrural:transferencias:catalogo:instituciones-financieras"
docker exec -it banrural-redis redis-cli TTL "banrural:transferencias:catalogo:instituciones-financieras"
```

---

## Paso 9: Probar el endpoint /1068 completo

```bash
curl -s "http://localhost:8080/api/v1/1068?usuario=USR001&oficina=001&canal=MOBILE" | python3 -m json.tool
```

**Resultado esperado:**

```json
{
    "usuario": "USR001",
    "oficina": "001",
    "canal": "MOBILE",
    "institucionesFinancieras": {
        "data": "{...}",
        "fromCache": true,
        "cacheKey": "catalogo:instituciones-financieras",
        "ttlRemainingSeconds": 45
    },
    "topCuentasDestino": "[pendiente - llamar API top cuentas destino]",
    "cuentasPropiasBanrural": "[pendiente - llamar API cuentas propias]",
    "cuentasTercerosBanrural": "[pendiente - llamar API cuentas terceros]",
    "cuentasOtrosBancos": "[pendiente - llamar API otros bancos]"
}
```

Notar que `institucionesFinancieras.fromCache` es `true` porque ya lo cacheamos en el paso 6.

---

## Paso 10: Invalidar el cache manualmente

Simula lo que pasaría cuando el catálogo se actualiza en el sistema origen:

```bash
curl -s -X DELETE http://localhost:8080/api/v1/cache/instituciones-financieras | python3 -m json.tool
```

**Resultado:**
```json
{
    "message": "Cache de instituciones financieras invalidado",
    "key": "catalogo:instituciones-financieras"
}
```

Verifica que Redis ya no tiene la key:

```bash
redis-cli GET "banrural:transferencias:catalogo:instituciones-financieras"
# (nil) ← vacío, cache borrado
```

---

## Paso 11: Tercera llamada — CACHE MISS de nuevo

Después de invalidar, la próxima consulta vuelve al API:

```bash
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool
```

**Resultado:** `"fromCache": false` — el ciclo empieza de nuevo.

---

## Paso 12: Ejecutar tests unitarios

```bash
mvn test
```

**Resultado esperado:**
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los tests verifican:
1. Cache MISS → llama al API y guarda en Redis
2. Cache HIT → no llama al API
3. Invalidación → elimina la key

---

## Paso 13: Probar con Redis de desarrollo y API real

Instancia Redis de desarrollo:

| Parámetro | Valor |
|-----------|-------|
| Host | `10.160.208.165` |
| Puerto | `6379` |
| Password | *(ninguna)* |

**Verificar conectividad a Redis:**

```bash
redis-cli -h 10.160.208.165 -p 6379 ping
# Debe responder: PONG
```

**Ejecutar con el perfil `dev`:**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Este perfil conecta automáticamente a `10.160.208.165:6379` y usa las URLs reales de los APIs.

**Probar:**

```bash
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool
```

**Verificar que el dato quedó en Redis de desarrollo:**

```bash
redis-cli -h 10.160.208.165 -p 6379 GET "banrural:transferencias:catalogo:instituciones-financieras"
redis-cli -h 10.160.208.165 -p 6379 TTL "banrural:transferencias:catalogo:instituciones-financieras"
```

---

## Resumen visual del flujo probado

```
Paso 6:  GET /instituciones  →  fromCache: false  →  API llamado  →  guardado en Redis
Paso 7:  GET /instituciones  →  fromCache: true   →  Redis HIT    →  sin llamar API
Paso 10: DELETE /cache/...   →  key eliminada    →  Redis vacío
Paso 11: GET /instituciones  →  fromCache: false  →  API llamado  →  guardado de nuevo
```

---

## Troubleshooting

| Problema | Causa probable | Solución |
|----------|---------------|----------|
| `Connection refused: localhost:6379` | Redis no está corriendo | `docker compose up -d` |
| `Connection refused: localhost:8080` | App no está corriendo | `mvn spring-boot:run -Dspring-boot.run.profiles=local` |
| Siempre `fromCache: false` | Redis no conecta o TTL=0 | Verificar `redis-cli ping` y config en `application.yml` |
| Error al llamar API real | Sin acceso a red interna | Usar perfil `local` con mock |
| `BUILD FAILURE` en Maven | Java < 17 | Instalar JDK 17+ y configurar `JAVA_HOME` |
| `python3: command not found` | Python no instalado | Instalar Python 3 o quitar `\| python3 -m json.tool` del curl |

---

## Script automatizado

Para ejecutar todos los pasos de una vez:

```bash
bash scripts/probar-cache.sh
```

(Requiere la app corriendo en localhost:8080)
