# Redis de Desarrollo — Conexión y Pruebas

Datos de la instancia Redis proporcionada para el ambiente de desarrollo.

---

## Datos de conexión

| Parámetro | Valor |
|-----------|-------|
| **Host** | `10.160.208.165` |
| **Puerto** | `6379` |
| **Password** | *(ninguna — no requiere autenticación)* |

---

## 1. Verificar conectividad

Desde una máquina con acceso a la red interna:

```bash
redis-cli -h 10.160.208.165 -p 6379 ping
```

**Respuesta esperada:** `PONG`

Si no responde, verificar:
- Estás conectado a la VPN o red corporativa
- Tu IP está autorizada en el firewall hacia `10.160.208.165:6379`
- El puerto 6379 no está bloqueado

---

## 2. Ejecutar la aplicación con Redis de desarrollo

El proyecto incluye el perfil `dev` que apunta automáticamente a esta instancia:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Configuración en `application-dev.yml`:

```yaml
spring:
  data:
    redis:
      host: 10.160.208.165
      port: 6379
      password:    # vacío — sin password
```

---

## 3. Probar el cache end-to-end

```bash
# 1. Primera llamada (CACHE MISS — llama al API y guarda en Redis)
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool

# 2. Segunda llamada (CACHE HIT — viene de Redis)
curl -s http://localhost:8080/api/v1/instituciones-financieras | python3 -m json.tool
# Verificar: "fromCache": true

# 3. Ver el dato guardado directamente en Redis de desarrollo
redis-cli -h 10.160.208.165 -p 6379 GET "banrural:transferencias:catalogo:instituciones-financieras"

# 4. Ver cuánto TTL le queda
redis-cli -h 10.160.208.165 -p 6379 TTL "banrural:transferencias:catalogo:instituciones-financieras"

# 5. Ver todas las keys del proyecto
redis-cli -h 10.160.208.165 -p 6379 KEYS "banrural:transferencias:*"
```

---

## 4. Invalidar cache en desarrollo

```bash
# Desde la API
curl -X DELETE http://localhost:8080/api/v1/cache/instituciones-financieras

# O directamente en Redis
redis-cli -h 10.160.208.165 -p 6379 DEL "banrural:transferencias:catalogo:instituciones-financieras"
```

---

## 5. Comandos útiles de Redis CLI

```bash
# Conectar a la instancia
redis-cli -h 10.160.208.165 -p 6379

# Dentro de redis-cli:
PING                                          # verificar conexión
KEYS banrural:transferencias:*                # listar keys del proyecto
GET banrural:transferencias:catalogo:instituciones-financieras
TTL banrural:transferencias:catalogo:instituciones-financieras
DEL banrural:transferencias:catalogo:instituciones-financieras
INFO memory                                   # uso de memoria
```

---

## 6. Perfiles disponibles — resumen

| Perfil | Redis | Password | API | Cuándo usar |
|--------|-------|----------|-----|-------------|
| `local` | `localhost:6379` (Docker) | — | Mock interno | Pruebas sin red interna |
| `dev` | `10.160.208.165:6379` | — | APIs reales | Pruebas con Redis corporativo |
| (default) | `localhost:6379` | — | APIs reales | Configuración manual |

---

## 7. Troubleshooting

| Problema | Solución |
|----------|----------|
| `Connection refused` a `10.160.208.165:6379` | Verificar VPN/red interna y firewall |
| `fromCache: false` siempre | Redis no conecta — revisar logs de Spring Boot |
| `Unable to connect to Redis` en logs | Confirmar `redis-cli -h 10.160.208.165 -p 6379 ping` |
| Key no aparece en Redis | Verificar que usaste perfil `dev` y no `local` |
| API de instituciones falla | Verificar acceso a `10.160.209.84:7080` |
