package com.riskdesk.domain.quant.automation;

import com.riskdesk.domain.quant.model.QuantSnapshot;

import java.lang.reflect.Method;

/**
 * Read-only view over a {@link QuantSnapshot} that hides the SHORT-only
 * legacy API and the LONG symmetry being added in parallel by PR #302.
 *
 * <p>Until PR #302 lands, the snapshot exposes only SHORT helpers
 * ({@link QuantSnapshot#shortAvailable()}, {@link QuantSnapshot#suggestedSL()},
 * etc). Once PR #302 merges, the snapshot will additionally expose
 * {@code longAvailable()}, {@code suggestedSL_LONG()}, etc. This view uses
 * reflection so the auto-arm pipeline compiles and works against either
 * version of the snapshot — when the LONG methods are absent the view simply
 * reports {@code longAvailable() == false}, blocking LONG arms gracefully.</p>
 *
 * <p>The view is intentionally pure (no caching, no Spring) and trivially
 * testable. After PR #302 lands the reflection layer can be deleted in favour
 * of a direct call.</p>
 */
public final class QuantSnapshotDirectionView {

    private static final Method LONG_AVAILABLE         = optionalMethod("longAvailable");
    private static final Method SUGGESTED_ENTRY_LONG   = optionalMethod("suggestedEntry_LONG");
    private static final Method SUGGESTED_SL_LONG      = optionalMethod("suggestedSL_LONG");
    private static final Method SUGGESTED_TP1_LONG     = optionalMethod("suggestedTP1_LONG");
    private static final Method SUGGESTED_TP2_LONG     = optionalMethod("suggestedTP2_LONG");
    private static final Method LONG_SCORE             = optionalMethod("longScore");

    private final QuantSnapshot snap;

    public QuantSnapshotDirectionView(QuantSnapshot snap) {
        if (snap == null) throw new IllegalArgumentException("snap is required");
        this.snap = snap;
    }

    /** True if the snapshot exposes LONG helpers (PR #302 has been merged). */
    public boolean longSupportPresent() {
        return LONG_AVAILABLE != null;
    }

    public boolean isAvailable(AutoArmDirection direction) {
        return switch (direction) {
            case SHORT -> snap.shortAvailable();
            case LONG  -> longSupportPresent() && Boolean.TRUE.equals(invokeBool(LONG_AVAILABLE));
        };
    }

    /**
     * Per-direction "final" score used to break ties when both directions are
     * available. SHORT uses {@link QuantSnapshot#finalScore()} (gates +
     * structural modifier). LONG uses the dedicated PR #301 long score when
     * available, otherwise falls back to {@code finalScore()} (which is fine
     * because LONG cannot be available without PR #301).
     */
    public int score(AutoArmDirection direction) {
        if (direction == AutoArmDirection.LONG && LONG_SCORE != null) {
            Object v = invoke(LONG_SCORE);
            if (v instanceof Number n) return n.intValue();
        }
        return snap.finalScore();
    }

    /**
     * Raw (pre-structural) per-direction gate count used by the min-score
     * gate. SHORT returns {@link QuantSnapshot#score()}; LONG returns
     * {@code snap.longScore()} when present, otherwise 0.
     */
    public int rawScore(AutoArmDirection direction, QuantSnapshot s) {
        if (direction == AutoArmDirection.SHORT) return s.score();
        if (LONG_SCORE != null) {
            Object v = invoke(LONG_SCORE);
            if (v instanceof Number n) return n.intValue();
        }
        return 0;
    }

    public Double suggestedEntry(AutoArmDirection direction) {
        return switch (direction) {
            case SHORT -> snap.suggestedEntry();
            case LONG  -> SUGGESTED_ENTRY_LONG != null ? (Double) invoke(SUGGESTED_ENTRY_LONG) : snap.suggestedEntry();
        };
    }

    public Double suggestedSL(AutoArmDirection direction) {
        return switch (direction) {
            case SHORT -> snap.suggestedSL();
            case LONG  -> SUGGESTED_SL_LONG != null ? (Double) invoke(SUGGESTED_SL_LONG) : null;
        };
    }

    public Double suggestedTP1(AutoArmDirection direction) {
        return switch (direction) {
            case SHORT -> snap.suggestedTP1();
            case LONG  -> SUGGESTED_TP1_LONG != null ? (Double) invoke(SUGGESTED_TP1_LONG) : null;
        };
    }

    public Double suggestedTP2(AutoArmDirection direction) {
        return switch (direction) {
            case SHORT -> snap.suggestedTP2();
            case LONG  -> SUGGESTED_TP2_LONG != null ? (Double) invoke(SUGGESTED_TP2_LONG) : null;
        };
    }

    private Object invoke(Method m) {
        try {
            return m.invoke(snap);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Boolean invokeBool(Method m) {
        Object v = invoke(m);
        return v instanceof Boolean b ? b : null;
    }

    private static Method optionalMethod(String name) {
        try {
            return QuantSnapshot.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
