package com.riskdesk.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards defaults for {@link MentorProperties}.
 *
 * <p>The {@code thinkingBudget} default is particularly load-bearing:
 * Gemini 3.x are thinking models and, without an explicit budget, they
 * silently consume most of {@code maxOutputTokens} for reasoning, which
 * truncates the JSON response mid-field. A prod audit (500 alerts,
 * 70 tradable signals) showed 96% of reviews falling back to
 * "Structured mentor response unavailable" because of this. If this
 * default gets reset to 0 or removed, that regression returns silently.
 */
class MentorPropertiesTest {

    @Test
    void thinkingBudget_hasSensibleDefault_sufficientForReasoningWithoutStarvingOutput() {
        MentorProperties props = new MentorProperties();

        // 512 leaves ~2488 tokens for the JSON schema at maxOutputTokens=3000.
        // If you tune this, make sure output budget stays >= 2000 or the
        // mentor response will truncate mid-verdict again.
        assertThat(props.getThinkingBudget()).isEqualTo(512);
    }

    @Test
    void thinkingBudget_isConfigurable() {
        MentorProperties props = new MentorProperties();
        props.setThinkingBudget(0);
        assertThat(props.getThinkingBudget()).isEqualTo(0);

        props.setThinkingBudget(1024);
        assertThat(props.getThinkingBudget()).isEqualTo(1024);
    }

    @Test
    void otherKeyDefaults_areStable() {
        MentorProperties props = new MentorProperties();

        // These are relied upon elsewhere; pin them so removals are loud.
        assertThat(props.getModel()).isEqualTo("gemini-3.1-pro-preview");
        assertThat(props.getEmbeddingsModel()).isEqualTo("gemini-embedding-001");
        assertThat(props.getEmbeddingDimensions()).isEqualTo(768);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isPersistAudits()).isTrue();
    }
}
