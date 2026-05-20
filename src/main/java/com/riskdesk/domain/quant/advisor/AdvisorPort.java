package com.riskdesk.domain.quant.advisor;

import com.riskdesk.domain.quant.memory.MemoryRecord;
import com.riskdesk.domain.quant.memory.SessionMemory;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;

import java.util.List;

/**
 * Output port. Implementations call out to a hosted LLM (Vertex AI / Gemini
 * API) and translate the response into a structured {@link AiAdvice}.
 */
public interface AdvisorPort {

    /** Returns the advisor's verdict for the given setup snapshot. */
    AiAdvice askAdvice(
        QuantSnapshot quant,
        PatternAnalysis pattern,
        SessionMemory memory,
        List<MemoryRecord> similarSituations,
        MultiInstrumentContext context
    );
}
