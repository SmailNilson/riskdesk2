package com.riskdesk.application.quant.adapter;

import com.riskdesk.application.quant.service.QuantAiAdvisorService;
import com.riskdesk.application.service.GeminiEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bridges the Quant advisor's lightweight embedding port to the existing
 * {@link GeminiEmbeddingClient}. Failure-safe: if Gemini is down, returns
 * {@code null} so the RAG step is skipped without crashing the whole flow.
 */
@Component
public class GeminiQuantEmbeddingAdapter implements QuantAiAdvisorService.EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiQuantEmbeddingAdapter.class);

    private final ObjectProvider<GeminiEmbeddingClient> clientProvider;

    public GeminiQuantEmbeddingAdapter(ObjectProvider<GeminiEmbeddingClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public float[] embed(String text) {
        GeminiEmbeddingClient client = clientProvider.getIfAvailable();
        if (client == null || text == null || text.isBlank()) return null;
        try {
            List<Double> values = client.embed(text);
            if (values == null || values.isEmpty()) return null;
            float[] out = new float[values.size()];
            for (int i = 0; i < values.size(); i++) out[i] = values.get(i).floatValue();
            return out;
        } catch (Exception ex) {
            log.warn("quant embedding failed: {}", ex.toString());
            return null;
        }
    }
}
