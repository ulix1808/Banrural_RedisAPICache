package com.banrural.cache.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InstitucionesFinancierasClient {

    private static final Logger log = LoggerFactory.getLogger(InstitucionesFinancierasClient.class);

    private final RestClient restClient;
    private final String apiUrl;

    public InstitucionesFinancierasClient(
            RestClient restClient,
            @Value("${banrural.api.instituciones-url}") String apiUrl) {
        this.restClient = restClient;
        this.apiUrl = apiUrl;
    }

    public String consultarInstituciones() {
        log.info("Llamando API instituciones → {}", apiUrl);
        return restClient.get().uri(apiUrl).retrieve().body(String.class);
    }
}
