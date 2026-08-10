# Cache de API — Guía conceptual

Este documento explica **qué es el cache de API**, **por qué usarlo**, y **cómo funciona** en el contexto del endpoint `/1068` de Banrural.

---

## 1. El problema sin cache

Imagina que 500 usuarios abren la pantalla de transferencias al mismo tiempo:

```
500 usuarios × 5 APIs = 2,500 llamadas HTTP en segundos
```

De esas 2,500 llamadas, al menos 500 son al **mismo catálogo de instituciones financieras** — el mismo JSON idéntico 500 veces. Eso es:
- Desperdicio de ancho de banda
- Carga innecesaria en el servidor del catálogo
- Latencia adicional para el usuario (cada llamada HTTP toma 50-500ms)

## 2. La solución: cache de API

En lugar de llamar al API cada vez, **guardas la respuesta la primera vez** y las siguientes consultas la leen de memoria.

```
Primera consulta:  Usuario → App → API Instituciones (200ms) → guarda en Redis → Usuario
Segunda consulta:  Usuario → App → Redis (1ms) → Usuario
Tercera consulta:  Usuario → App → Redis (1ms) → Usuario
...
Después de 1 hora: TTL expira → próxima consulta vuelve al API
```

**Resultado:** de 200ms a 1ms. Una mejora de ~200x en latencia para datos cacheados.

## 3. Patrones de cache

Existen 3 patrones principales. Este proyecto usa **Cache-Aside**.

### Cache-Aside (Lazy Loading) — EL QUE USAMOS

```
┌─────────┐                    ┌───────┐
│   App   │──1. GET key───────▶│ Redis │
│         │◀─2a. HIT: data───│       │
│         │                    └───────┘
│         │──2b. MISS ──────────────────┐
│         │                              ▼
│         │                    ┌──────────────┐
│         │──3. GET ──────────▶│  API externo │
│         │◀─4. response ──────│              │
│         │                    └──────────────┘
│         │──5. SET key,data──▶┌───────┐
│         │                    │ Redis │
└─────────┘                    └───────┘
```

**Ventajas:**
- Simple de implementar
- La app controla qué se cachea y cuándo
- Si Redis falla, la app sigue funcionando (llama al API directamente)

**Desventajas:**
- La primera consulta siempre es lenta (cache miss)
- Datos pueden estar desactualizados hasta que expire el TTL

### Write-Through (no usado aquí)

Cada vez que se escribe en la base de datos, también se escribe en cache. Más complejo, útil cuando la app es dueña de los datos.

### Read-Through (no usado aquí)

Redis actúa como proxy: si no tiene el dato, lo busca automáticamente. Requiere configuración especial en Redis.

## 4. Componentes de un cache en Redis

### KEY (clave)

Identificador único del dato. Debe ser:
- **Predecible:** misma consulta = misma key
- **Única:** diferentes datos = diferentes keys
- **Con prefijo:** evitar colisiones con otras apps

```
# Catálogo global (mismo para todos los usuarios)
banrural:transferencias:catalogo:instituciones-financieras

# Dato por usuario (diferente por cada usuario)
banrural:transferencias:usuario:USR001:cuentas-propias
```

### VALUE (valor)

El contenido cacheado. En nuestro caso, el **JSON completo** tal como lo devuelve el API:

```json
{
  "codigo": "00",
  "mensaje": "Consulta exitosa",
  "instituciones": [
    {"codigo": "001", "nombre": "Banco de Guatemala", "tipo": "ACH"},
    {"codigo": "002", "nombre": "Banrural", "tipo": "ACH"}
  ]
}
```

Guardamos el JSON como **string** en Redis. No parseamos ni transformamos — eso mantiene el cache simple y desacoplado del modelo de datos.

### TTL (Time To Live)

Tiempo en segundos antes de que Redis **borre automáticamente** la key.

| Tipo de dato | TTL recomendado | Razón |
|-------------|----------------|-------|
| Catálogo instituciones | 1 hora (3600s) | Cambia muy raramente |
| Top cuentas destino | 30 min (1800s) | Casi estático |
| Cuentas por usuario | 5 min (300s) | Puede cambiar si el usuario agrega cuentas |
| Datos transaccionales | No cachear | Siempre deben ser en tiempo real |

**¿Qué pasa cuando expira el TTL?**
1. Redis elimina la key automáticamente
2. La próxima consulta es un cache MISS
3. Se llama al API, se guarda de nuevo con TTL renovado

No necesitas hacer nada manual — Redis se encarga.

## 5. Cache HIT vs Cache MISS

### HIT (acierto)

El dato estaba en Redis. **No se llama al API.**

```
Request → Redis GET key → valor encontrado → respuesta inmediata
Tiempo: ~1ms
```

En los logs verás:
```
CACHE HIT  → key=banrural:transferencias:catalogo:instituciones-financieras
Instituciones financieras servidas desde CACHE (ttl=2847s)
```

### MISS (fallo)

El dato NO estaba en Redis. **Se llama al API y se guarda.**

```
Request → Redis GET key → null → HTTP GET al API → Redis SET key → respuesta
Tiempo: ~200ms (depende del API)
```

En los logs verás:
```
CACHE MISS → key=banrural:transferencias:catalogo:instituciones-financieras
Cache miss → consultando API de instituciones financieras
Llamando API instituciones financieras → http://10.160.209.84:7080/...
CACHE SET  → key=banrural:transferencias:catalogo:instituciones-financieras, ttl=3600s
```

## 6. Invalidación de cache

A veces necesitas borrar el cache **antes** de que expire el TTL:

- El catálogo de instituciones se actualizó en el sistema origen
- Se desplegó una nueva versión del API con datos diferentes
- Pruebas en desarrollo

### Invalidación manual (implementada)

```bash
curl -X DELETE http://localhost:8080/api/v1/cache/instituciones-financieras
```

Esto ejecuta `RedisCacheService.evict(key)` que hace `DEL` en Redis.

### Invalidación por TTL (automática)

Configurada en `application.yml`:
```yaml
banrural:
  cache:
    catalog-ttl-seconds: 3600  # 1 hora
```

### Estrategias de invalidación en producción

| Estrategia | Cuándo usar | Cómo |
|-----------|------------|------|
| TTL automático | Catálogos que cambian poco | Configurar `catalog-ttl-seconds` |
| Invalidación manual | Actualización puntual del catálogo | Endpoint DELETE /cache/... |
| Event-driven | Cuando el sistema origen notifica cambios | Webhook o mensaje que llama al DELETE |
| Versionado de key | Deploy de nueva versión | Cambiar prefijo: `catalogo:v2:instituciones` |

## 7. ¿Qué APIs cachear y cuáles no?

### Sí cachear (catálogos estáticos)

| API | Key | TTL | Razón |
|-----|-----|-----|-------|
| Instituciones financieras | `catalogo:instituciones-financieras` | 1h | 100% catálogo, mismo para todos |
| Top cuentas destino | `catalogo:top-cuentas` o `usuario:{id}:top-cuentas` | 30min | Casi estático |

### Cachear con precaución (datos por usuario)

| API | Key | TTL | Razón |
|-----|-----|-----|-------|
| Cuentas propias | `usuario:{id}:cuentas-propias` | 5min | Puede cambiar si el usuario agrega cuenta |
| Cuentas terceros | `usuario:{id}:cuentas-terceros` | 5min | Puede cambiar |
| Cuentas otros bancos | `usuario:{id}:cuentas-otros-bancos` | 5min | Puede cambiar |

### No cachear

- Saldos de cuentas
- Resultados de transferencias
- OTP / tokens de seguridad
- Cualquier dato que deba ser en tiempo real

## 8. Código de referencia — el corazón del cache

Este es el método que implementa Cache-Aside en el proyecto:

```java
public CachedApiResult obtenerInstituciones() {
    // Paso 1: Buscar en Redis
    String cached = cacheService.get(CACHE_KEY);
    if (cached != null) {
        // HIT → devolver sin llamar al API
        return CachedApiResult.fromCache(cached, CACHE_KEY, ttl);
    }

    // Paso 2: MISS → llamar al API
    String apiResponse = apiClient.consultarInstituciones();

    // Paso 3: Guardar en Redis
    cacheService.put(CACHE_KEY, apiResponse, ttl);

    // Paso 4: Devolver respuesta
    return CachedApiResult.fromApi(apiResponse, CACHE_KEY);
}
```

Solo 4 pasos. Este mismo patrón se repite para cada catálogo que quieran cachear.

## 9. Preguntas frecuentes

**¿Qué pasa si Redis se cae?**
La aplicación puede seguir funcionando llamando directamente al API (sin cache). El rendimiento baja pero el servicio no se cae. Se puede agregar un try/catch alrededor de Redis para degradar gracefully.

**¿Cuánta memoria usa Redis?**
Un catálogo de instituciones financieras en JSON pesa ~5-50 KB. Incluso con 100 catálogos cacheados, son pocos MB. Redis puede manejar GB sin problema.

**¿Cómo sé si el cache está funcionando?**
1. El campo `fromCache: true` en la respuesta JSON
2. Los logs muestran `CACHE HIT` vs `CACHE MISS`
3. `redis-cli KEYS "banrural:*"` muestra las keys activas
4. La segunda llamada es notablemente más rápida

**¿Puedo ver qué hay en Redis?**
```bash
# Ver todas las keys del proyecto
redis-cli KEYS "banrural:transferencias:*"

# Ver el contenido de una key
redis-cli GET "banrural:transferencias:catalogo:instituciones-financieras"

# Ver cuánto le queda de vida
redis-cli TTL "banrural:transferencias:catalogo:instituciones-financieras"
```

**¿Necesito cambiar algo en el API original?**
No. El cache vive en la aplicación Java (/1068). El API de instituciones financieras no sabe que existe un cache — sigue funcionando igual.
