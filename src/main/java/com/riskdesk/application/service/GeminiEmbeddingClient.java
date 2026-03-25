package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GeminiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private final MentorProperties properties;
    private final RestTemplate restTemplate;

    public GeminiEmbeddingClient(MentorProperties properties) {
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties);
    }

    public List<Double> embed(String text) {
        return embed(text, properties.getEmbeddingsModel());
    }

    public List<Double> embed(String text, String modelName) {
        if (!properties.isEnabled() || !properties.isEmbeddingsEnabled()) {
            throw new IllegalStateException("Mentor embeddings are disabled.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured on the backend.");
        }

        try {
            Map<String, Object> body = Map.of(
                "model", "models/" + modelName,
                "content", Map.of(
                    "parts", List.of(Map.of("text", text))
                ),
                "outputDimensionality", properties.getEmbeddingDimensions()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", properties.getApiKey());

            String endpoint = properties.getEndpoint()
                + "/v1beta/models/"
                + modelName
                + ":embedContent";

            JsonNode root = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
            ).getBody();

            JsonNode values = root.path("embedding").path("values");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("Gemini embedding response was empty.");
            }

            List<Double> embedding = new ArrayList<>(values.size());
            for (JsonNode value : values) {
                embedding.add(value.asDouble());
            }
            return embedding;
        } catch (Exception e) {
            log.error("Gemini embedding call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Gemini embedding call failed: " + e.getMessage(), e);
        }
    }

    private RestTemplate buildRestTemplate(MentorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        return new RestTemplate(factory);
    }
}
