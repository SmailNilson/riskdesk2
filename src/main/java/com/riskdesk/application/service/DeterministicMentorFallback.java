package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskdesk.application.dto.MentorAnalyzeResponse;
import com.riskdesk.application.dto.MentorProposedTradePlan;
import com.riskdesk.application.dto.MentorStructuredResponse;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Deterministic fallback used when the Gemini mentor call fails entirely
 * (network error, API key revoked, timeout, etc.).
 *
 * <p>Unlike {@code MentorSignalReviewService#applyPlaybookFallback} — which
 * overrides a degraded-but-parseable Gemini verdict when the deterministic
 * playbook scored highly — this component is invoked when there is no
 * Gemini response at all. It synthesises a {@link MentorAnalyzeResponse}
 * from the payload so the review is persisted as DONE/INELIGIBLE instead of
 * being lost as ERROR.
 *
 * <p><b>Policy:</b> the synthetic response is always {@code INELIGIBLE}.
 * Auto-execution must never fire when the AI mentor is offline — the human
 * operator reviews the mechanical plan manually. This is a conservative
 * stand-down, not a playbook-based auto-promotion.
 *
 * <p>The mechanical plan from {@code playbook_pre_analysis.mechanical_plan}
 * is preserved on {@code proposedTradePlan} so the UI can still display
 * entry/SL/TP for the operator.
 */
@Component
public class DeterministicMentorFallback {

    /** Model name used to mark the synthetic response in persistence / UI. */
    public static final String FALLBACK_MODEL = "deterministic-fallback";

    /** Verdict string attached to every synthetic response. */
    public static final String FALLBACK_VERDICT = "Mentor Indisponible - Fallback Deterministe";

    /**
     * Build a deterministic response that represents the review as seen from
     * the playbook + agents (no Gemini input).
     *
     * @param payload the payload that would have been sent to Gemini
     * @param reason  short reason why Gemini was unavailable (used in the UI / logs)
     */
    public MentorAnalyzeResponse build(JsonNode payload, String reason) {
        String safeReason = (reason == null || reason.isBlank()) ? "Gemini mentor unavailable" : reason;

        JsonNode pb = payload == null ? null : payload.get("playbook_pre_analysis");

        String checklistScore = null;
        String playbookVerdict = null;
        List<String> strengths = new ArrayList<>();
        MentorProposedTradePlan plan = null;

        if (pb != null && pb.isObject()) {
            checklistScore = textOrNull(pb, "checklist_score");
            playbookVerdict = textOrNull(pb, "verdict");

            // Keep a few checklist items as "strengths" so the operator sees why
            // the playbook accepted the setup even though Gemini is unavailable.
            JsonNode items = pb.get("checklist_items");
            if (items != null && items.isArray()) {
                int max = Math.min(items.size(), 5);
                for (int i = 0; i < max; i++) {
                    JsonNode it = items.get(i);
                    if (it == null) continue;
                    String label = textOrNull(it, "label");
                    Boolean passed = booleanOrNull(it, "passed");
                    if (label != null && passed != null && passed) {
                        strengths.add(label);
                    }
                }
            }
            if (playbookVerdict != null && !playbookVerdict.isBlank()) {
                strengths.add("Playbook: " + playbookVerdict);
            }

            plan = extractMechanicalPlan(pb.get("mechanical_plan"));
        }

        String technical = buildTechnicalSummary(payload, checklistScore, playbookVerdict);

        List<String> errors = List.of(
            "Mentor IA indisponible: " + safeReason,
            "Fallback deterministe applique - revue humaine requise avant execution."
        );

        String eligibilityReason = buildEligibilityReason(safeReason, checklistScore);

        MentorStructuredResponse structured = new MentorStructuredResponse(
            technical,
            List.copyOf(strengths),
            errors,
            FALLBACK_VERDICT,
            ExecutionEligibilityStatus.INELIGIBLE,
            eligibilityReason,
            "Surveille le statut du mentor IA. Le plan mecanique reste affiche pour revue manuelle.",
            plan
        );

        return new MentorAnalyzeResponse(
            null,              // no audit row — Gemini never returned
            FALLBACK_MODEL,
            payload,
            structured,
            null,              // rawResponse
            List.of()          // similarAudits
        );
    }

    private static String buildTechnicalSummary(JsonNode payload, String score, String pbVerdict) {
        String asset = payload == null ? null : textOrNull(payload.path("metadata"), "asset");
        String tf = payload == null ? null : textOrNull(payload.path("metadata"), "timeframe_focus");
        String action = payload == null ? null : textOrNull(payload.path("trade_intention"), "action");
        String structureEvent = payload == null ? null
            : textOrNull(payload.path("market_structure_the_king"), "last_event");

        StringBuilder sb = new StringBuilder(192);
        sb.append("Mentor IA indisponible - synthese deterministe: ");
        if (asset != null) sb.append(asset);
        if (action != null) sb.append(' ').append(action);
        if (tf != null) sb.append(" [").append(tf).append(']');
        if (structureEvent != null) sb.append(" - structure: ").append(structureEvent);
        if (score != null && !score.isBlank()) sb.append(" - checklist ").append(score);
        if (pbVerdict != null && !pbVerdict.isBlank()) sb.append(" - playbook ").append(pbVerdict);
        sb.append('.');
        return sb.toString();
    }

    private static String buildEligibilityReason(String reason, String checklistScore) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("Mentor IA offline - conservative standdown. Raison: ").append(reason).append('.');
        if (checklistScore != null && !checklistScore.isBlank()) {
            sb.append(" Playbook score: ").append(checklistScore).append('.');
        }
        sb.append(" Execution auto bloquee; plan affiche pour revue humaine.");
        return sb.toString();
    }

    private static MentorProposedTradePlan extractMechanicalPlan(JsonNode mech) {
        if (mech == null || !mech.isObject()) return null;

        Double entry = numberOrNull(mech, "entry");
        Double sl = numberOrNull(mech, "sl");
        Double tp = numberOrNull(mech, "tp1");
        Double rr = numberOrNull(mech, "rr");
        if (entry == null && sl == null && tp == null && rr == null) {
            return null;
        }

        String slRationale = textOrNull(mech, "sl_rationale");
        return new MentorProposedTradePlan(
            entry, sl, tp, rr,
            slRationale != null ? slRationale : "Plan mecanique playbook (Mentor IA indisponible).",
            null,
            "PLAYBOOK_MECHANICAL"
        );
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        return child.isTextual() ? child.asText() : child.toString();
    }

    private static Double numberOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        if (child.isNumber()) return child.doubleValue();
        if (child.isTextual()) {
            try {
                return Double.parseDouble(child.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean booleanOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) return null;
        if (child.isBoolean()) return child.booleanValue();
        if (child.isTextual()) {
            String t = child.asText().trim().toLowerCase();
            if ("true".equals(t)) return Boolean.TRUE;
            if ("false".equals(t)) return Boolean.FALSE;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static List<String> readStringList(JsonNode node, String field) {
        if (node == null) return List.of();
        JsonNode child = node.get(field);
        if (child == null || !child.isArray()) return List.of();
        List<String> out = new ArrayList<>(child.size());
        for (Iterator<JsonNode> it = child.elements(); it.hasNext();) {
            JsonNode el = it.next();
            if (el != null && !el.isNull() && el.isTextual()) {
                out.add(el.asText());
            }
        }
        return out;
    }
}
