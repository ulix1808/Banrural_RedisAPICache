package com.banrural.cache.service;

import com.banrural.cache.client.InstitucionesFinancierasClient;
import com.banrural.cache.config.CacheProperties;
import com.banrural.cache.model.CachedApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstitucionesFinancierasServiceTest {

    @Mock
    private RedisCacheService cacheService;

    @Mock
    private InstitucionesFinancierasClient apiClient;

    private InstitucionesFinancierasService service;

    @BeforeEach
    void setUp() {
        CacheProperties props = new CacheProperties(3600, "test:");
        service = new InstitucionesFinancierasService(cacheService, apiClient, props);
    }

    @Test
    void cacheMiss_llamaApiYGuardaEnRedis() {
        String jsonApi = "{\"instituciones\":[]}";
        when(cacheService.get(InstitucionesFinancierasService.CACHE_KEY)).thenReturn(null);
        when(apiClient.consultarInstituciones()).thenReturn(jsonApi);

        CachedApiResult result = service.obtenerInstituciones();

        assertThat(result.fromCache()).isFalse();
        assertThat(result.data()).isEqualTo(jsonApi);
        verify(cacheService).put(InstitucionesFinancierasService.CACHE_KEY, jsonApi, 3600);
        verify(apiClient).consultarInstituciones();
    }

    @Test
    void cacheHit_noLlamaApi() {
        String jsonCache = "{\"instituciones\":[{\"codigo\":\"001\"}]}";
        when(cacheService.get(InstitucionesFinancierasService.CACHE_KEY)).thenReturn(jsonCache);
        when(cacheService.getTtlRemaining(InstitucionesFinancierasService.CACHE_KEY)).thenReturn(1800L);

        CachedApiResult result = service.obtenerInstituciones();

        assertThat(result.fromCache()).isTrue();
        assertThat(result.data()).isEqualTo(jsonCache);
        assertThat(result.ttlRemainingSeconds()).isEqualTo(1800L);
        verify(apiClient, never()).consultarInstituciones();
        verify(cacheService, never()).put(any(), any(), anyLong());
    }

    @Test
    void invalidarCache_eliminaKey() {
        service.invalidarCache();
        verify(cacheService).evict(InstitucionesFinancierasService.CACHE_KEY);
    }
}
