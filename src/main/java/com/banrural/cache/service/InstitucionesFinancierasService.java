package com.banrural.cache.service;

import com.banrural.cache.client.InstitucionesFinancierasClient;
import com.banrural.cache.config.CacheProperties;
import com.banrural.cache.model.CachedApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Servicio que implementa Cache-Aside para el catálogo de instituciones financieras.
 *
 * ┌─────────┐     ┌──────────────┐     ┌─────────────────────┐
 * │ Cliente │────▶│  Este servicio│────▶│ Redis (cache)       │
 * └─────────┘     └──────┬───────┘     └─────────────────────┘
 *                        │ MISS
 *                        ▼
 *               ┌─────────────────────┐
 *               │ API Instituciones   │
 *               │ (catálogo REST)     │
 *               └─────────────────────┘
 */
@Service
public class InstitucionesFinancierasService {

    private static final Logger log = LoggerFactory.getLogger(InstitucionesFinancierasService.class);

    /** Key fija porque el catálogo es global (no depende de usuario/oficina/canal). */
    public static final String CACHE_KEY = "catalogo:instituciones-financieras";

    private final RedisCacheService cacheService;
    private final InstitucionesFinancierasClient apiClient;
    private final CacheProperties cacheProperties;

    public InstitucionesFinancierasService(
            RedisCacheService cacheService,
            InstitucionesFinancierasClient apiClient,
            CacheProperties cacheProperties) {
        this.cacheService = cacheService;
        this.apiClient = apiClient;
        this.cacheProperties = cacheProperties;
    }

    /**
     * Obtiene el catálogo de instituciones financieras.
     * Primero busca en Redis; si no existe, llama al API y guarda el resultado.
     */
    public CachedApiResult obtenerInstituciones() {
        // Paso 1: Intentar leer de Redis
        String cached = cacheService.get(CACHE_KEY);
        if (cached != null) {
            Long ttl = cacheService.getTtlRemaining(CACHE_KEY);
            log.info("Instituciones financieras servidas desde CACHE (ttl={}s)", ttl);
            return CachedApiResult.fromCache(cached, CACHE_KEY, ttl);
        }

        // Paso 2: Cache miss → llamar al API downstream
        log.info("Cache miss → consultando API de instituciones financieras");
        String apiResponse = apiClient.consultarInstituciones();

        // Paso 3: Guardar en Redis con TTL
        long ttl = cacheProperties.catalogTtlSeconds();
        cacheService.put(CACHE_KEY, apiResponse, ttl);
        log.info("Respuesta guardada en Redis con TTL={}s", ttl);

        return CachedApiResult.fromApi(apiResponse, CACHE_KEY);
    }

    /**
     * Fuerza la invalidación del cache (útil cuando el catálogo se actualiza).
     */
    public void invalidarCache() {
        cacheService.evict(CACHE_KEY);
        log.info("Cache de instituciones financieras invalidado manualmente");
    }
}
