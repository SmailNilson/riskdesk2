package com.riskdesk.application.quant.setup.gate;

import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.setup.SetupGate;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Passes only during tradeable session windows in America/New_York:
 * <ul>
 *   <li>London open / NY overlap: 02:00 – 17:00 ET</li>
 *   <li>Weekend (Sat all-day, Sun before 17:00): blocked</li>
 * </ul>
 * This deliberately allows a wide window so the gate is permissive for
 * overnight and early-morning setups that the desk sometimes trades.
 */
public class SessionGate implements SetupGate {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final int SESSION_START_HOUR = 2;   // 02:00 ET
    private static final int SESSION_END_HOUR   = 17;  // 17:00 ET

    @Override
    public GateCheckResult check(SetupEvaluationContext ctx) {
        ZonedDateTime etNow = ctx.evaluatedAt().atZone(ET);
        int dow  = etNow.getDayOfWeek().getValue(); // 1=Mon … 7=Sun
        int hour = etNow.getHour();

        // Saturday: always blocked (CME closed from Fri 17:00 to Sun 17:00)
        if (dow == 6) {
            return GateCheckResult.fail("SESSION", "weekend — market closed (Saturday)");
        }
        // Sunday before 17:00: market still closed
        if (dow == 7 && hour < 17) {
            return GateCheckResult.fail("SESSION", "weekend — market opens Sun 17:00 ET");
        }
        // Within active window
        if (hour >= SESSION_START_HOUR && hour < SESSION_END_HOUR) {
            return GateCheckResult.pass("SESSION", "active window hour=" + hour + " ET");
        }
        return GateCheckResult.fail("SESSION",
            "outside active window (02:00–17:00 ET), hour=" + hour);
    }
}
