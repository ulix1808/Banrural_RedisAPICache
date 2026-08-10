# Arquitectura — Cache Redis para Transferencias

## Contexto del negocio

**Pantalla:** Consulta de cuentas terceros y ACH  
**Endpoint:** `/1068` (Java)  
**Parámetros de entrada:** `usuario`, `oficina`, `canal`  
**Formato de respuesta:** JSON / XML

---

## Diagrama general

```
                          ┌─────────────────────────────────────────────┐
                          │           CANAL (App Móvil / Web)           │
                          └──────────────────────┬──────────────────────┘
                                                 │
                                    GET /1068?usuario&oficina&canal
                                                 │
                          ┌──────────────────────▼──────────────────────┐
                          │         ENDPOINT /1068 (Java - Spring Boot)   │
                          │                                               │
                          │  TransferenciasController                       │
                          │       │                                       │
                          │       ▼                                       │
                          │  TransferenciasOrchestratorService             │
                          │       │                                       │
                          │       ├── InstitucionesFinancierasService ◄── CACHE │
                          │       ├── TopCuentasService          (futuro) │
                          │       ├── CuentasPropiasService      (futuro) │
                          │       ├── CuentasTercerosService     (futuro) │
                          │       └── CuentasOtrosBancosService  (futuro) │
                          └───────┬──────────────────────┬────────────────┘
                                  │                      │
                     ┌────────────▼────────┐    ┌────────▼────────────┐
                     │       REDIS         │    │   APIs DOWNSTREAM   │
                     │                     │    │                     │
                     │  KEY: catalogo:     │    │  1. Instituciones   │
                     │    instituciones    │    │     (REST/Java)     │
                     │  VALUE: JSON        │    │  2. Top Cuentas     │
                     │  TTL: 3600s         │    │     (REST/Java)     │
                     │                     │    │  3. Cuentas Propias │
                     └─────────────────────┘    │     (REST/Java)     │
                                              │  4. Terceros        │
                                              │     (.NET ASMX)     │
                                              │  5. Otros Bancos    │
                                              │     (ESB)           │
                                              └─────────────────────┘
```

---

## Flujo de cache — Instituciones Financieras

### Escenario 1: Cache MISS (primera consulta o TTL expirado)

```
Tiempo →

Usuario ──GET /1068──▶ Controller ──▶ InstFinService
                                           │
                                    GET key ──▶ Redis ──▶ null (MISS)
                                           │
                                    GET url ──▶ API Instituciones
                                           │         │
                                           │    ◄────┘ JSON response (200ms)
                                           │
                                    SET key,json,ttl ──▶ Redis
                                           │
Usuario ◄── JSON response ────────────────┘
         
Total: ~200ms
```

### Escenario 2: Cache HIT (consultas subsecuentes)

```
Tiempo →

Usuario ──GET /1068──▶ Controller ──▶ InstFinService
                                           │
                                    GET key ──▶ Redis ──▶ JSON (HIT)
                                           │
Usuario ◄── JSON response ────────────────┘

Total: ~1ms
```

---

## Clasificación de APIs para estrategia de cache

```
┌─────────────────────────────────────────────────────────────────┐
│                    MATRIZ DE CACHE                              │
│                                                                 │
│  Frecuencia de cambio                                           │
│  ▲                                                              │
│  │  NO CACHEAR          │  CACHE CORTO (5min)                  │
│  │  · Saldos             │  · Cuentas terceros                 │
│  │  · Transferencias     │  · Cuentas otros bancos             │
│  │  · OTP                │  · Cuentas propias                  │
│  │                       │                                     │
│  ├───────────────────────┼──────────────────────────────────── │
│  │  CACHE LARGO (30min)   │  CACHE GLOBAL (1h)                 │
│  │  · Top cuentas destino│  · Instituciones financieras ★     │
│  │                       │                                     │
│  └───────────────────────┴────────────────────────────────────▶│
│                          Dependencia de usuario                  │
│                    (global)              (por usuario)           │
└─────────────────────────────────────────────────────────────────┘

★ = Implementado en este proyecto
```

---

## Estructura de keys en Redis

```
banrural:transferencias:                          ← prefijo global
    │
    ├── catalogo:                                  ← datos globales
    │   ├── instituciones-financieras             ← TTL: 3600s ★
    │   └── top-cuentas                           ← TTL: 1800s (futuro)
    │
    └── usuario:{userId}:                         ← datos por usuario
        ├── top-cuentas                           ← TTL: 300s (futuro)
        ├── cuentas-propias                       ← TTL: 300s (futuro)
        ├── cuentas-terceros                      ← TTL: 300s (futuro)
        └── cuentas-otros-bancos                  ← TTL: 300s (futuro)
```

---

## Stack tecnológico

| Componente | Tecnología | Versión |
|-----------|-----------|---------|
| Lenguaje | Java | 17+ |
| Framework | Spring Boot | 3.2.5 |
| Cache | Redis | 7.x |
| Cliente Redis | Spring Data Redis | (incluido en Boot) |
| HTTP Client | RestClient (Spring 6) | (incluido en Boot) |
| Build | Maven | 3.8+ |
| Contenedor Redis | Docker | docker-compose |

---

## Ambientes y conectividad

### Desarrollo local

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
│  curl/Postman│────▶│  Spring Boot │────▶│  Redis (Docker)  │
│              │     │  :8080       │     │  :6379           │
└──────────────┘     └──────┬───────┘     └──────────────────┘
                            │
                            ▼
                   ┌──────────────────┐
                   │  Mock API        │
                   │  (interno :8080) │
                   └──────────────────┘
```

### Desarrollo (red interna)

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────────┐
│  App/Canal   │────▶│  Spring Boot │────▶│  Redis Desarrollo    │
│              │     │  :8080       │     │  10.160.208.165:6379 │
└──────────────┘     └──────┬───────┘     │  (sin password)      │
                            │             └──────────────────────┘
                            ▼
                   ┌──────────────────┐
                   │  API Instituciones│
                   │  10.160.209.84   │
                   │  :7080           │
                   └──────────────────┘

Perfil: dev
Comando: mvn spring-boot:run -Dspring-boot.run.profiles=dev

Requisitos:
  - IP autorizada en firewall
  - VPN o acceso a red corporativa
```

---

## Capas del código

```
┌─────────────────────────────────────────────────┐
│  CAPA DE EXPOSICIÓN (Controller)                │
│  TransferenciasController                       │
│  · Recibe HTTP requests                         │
│  · Valida parámetros                            │
│  · Retorna JSON                                 │
├─────────────────────────────────────────────────┤
│  CAPA DE NEGOCIO (Service)                      │
│  InstitucionesFinancierasService                │
│  TransferenciasOrchestratorService              │
│  · Lógica de cache (Cache-Aside)                │
│  · Orquestación de múltiples APIs               │
│  · Decisión HIT/MISS                            │
├─────────────────────────────────────────────────┤
│  CAPA DE CACHE (Service)                        │
│  RedisCacheService                              │
│  · get / put / evict / ttl                      │
│  · Genérico, reutilizable                       │
├─────────────────────────────────────────────────┤
│  CAPA DE INTEGRACIÓN (Client)                  │
│  InstitucionesFinancierasClient                 │
│  · HTTP calls a APIs externos                   │
│  · Manejo de errores / timeouts                 │
├─────────────────────────────────────────────────┤
│  CAPA DE INFRAESTRUCTURA (Config)              │
│  AppConfig, CacheProperties, ServicesProperties │
│  · Conexión Redis                               │
│  · RestClient                                   │
│  · URLs y TTLs configurables                    │
└─────────────────────────────────────────────────┘
```
