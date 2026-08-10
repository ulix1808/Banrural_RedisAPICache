package com.banrural.cache.controller;

import com.banrural.cache.service.InstitucionesFinancierasService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CatalogoController {

    private final InstitucionesFinancierasService institucionesService;

    public CatalogoController(InstitucionesFinancierasService institucionesService) {
        this.institucionesService = institucionesService;
    }

    @GetMapping("/instituciones-financieras")
    public ResponseEntity<?> consultarInstituciones() {
        return ResponseEntity.ok(institucionesService.obtenerInstituciones());
    }

    @DeleteMapping("/cache/instituciones-financieras")
    public ResponseEntity<Map<String, String>> invalidarCache() {
        institucionesService.invalidarCache();
        return ResponseEntity.ok(Map.of(
                "message", "Cache invalidado",
                "key", InstitucionesFinancierasService.CACHE_KEY
        ));
    }
}
