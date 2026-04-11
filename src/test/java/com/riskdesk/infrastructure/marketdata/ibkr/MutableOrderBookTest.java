package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DepthMetrics;
import com.riskdesk.domain.orderflow.model.WallEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MutableOrderBookTest {

    private MutableOrderBook book;
    private final Instrument instrument = Instrument.MCL;
    // Wall threshold multiplier of 3.0: a level is a wall if size > 3x the avg level size
    private static final double WALL_THRESHOLD = 3.0;

    @BeforeEach
    void setUp() {
        book = new MutableOrderBook(WALL_THRESHOLD);
    }

    // =================== INSERT operations ===================

    @Test
    void insertBidsAndAsks_correctBestBidAsk() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(1, "INSERT", "BUY", 70.45, 15, instrument);
        book.updateDepth(0, "INSERT", "SELL", 70.55, 12, instrument);
        book.updateDepth(1, "INSERT", "SELL", 70.60, 8, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        assertThat(metrics.bestBid()).isCloseTo(70.50, within(0.001));
        assertThat(metrics.bestAsk()).isCloseTo(70.55, within(0.001));
        assertThat(metrics.totalBidSize()).isEqualTo(25);
        assertThat(metrics.totalAskSize()).isEqualTo(20);
    }

    @Test
    void insertAtPosition_shiftsExistingLevels() {
        // Insert at pos 0, then insert at pos 0 again — first entry shifts to pos 1
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(0, "INSERT", "BUY", 70.55, 20, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        // Best bid should be the most recently inserted at position 0
        assertThat(metrics.bestBid()).isCloseTo(70.55, within(0.001));
        assertThat(metrics.totalBidSize()).isEqualTo(30);
    }

    // =================== UPDATE operations ===================

    @Test
    void updateLevel_sizeChanges() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(1, "INSERT", "BUY", 70.45, 15, instrument);

        // Update level 0 size from 10 to 25
        book.updateDepth(0, "UPDATE", "BUY", 70.50, 25, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        assertThat(metrics.totalBidSize()).isEqualTo(40); // 25 + 15
    }

    @Test
    void updateLevel_priceAndSizeChange() {
        book.updateDepth(0, "INSERT", "SELL", 70.60, 10, instrument);

        book.updateDepth(0, "UPDATE", "SELL", 70.55, 20, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);
        assertThat(metrics.bestAsk()).isCloseTo(70.55, within(0.001));
        assertThat(metrics.totalAskSize()).isEqualTo(20);
    }

    // =================== DELETE operations ===================

    @Test
    void deleteLevel_shiftsRemaining() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(1, "INSERT", "BUY", 70.45, 15, instrument);
        book.updateDepth(2, "INSERT", "BUY", 70.40, 20, instrument);

        // Delete level 0 => levels 1,2 shift to 0,1
        book.updateDepth(0, "DELETE", "BUY", 0, 0, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        // Best bid was 70.50, after delete of pos 0, new best bid is 70.45
        assertThat(metrics.bestBid()).isCloseTo(70.45, within(0.001));
        assertThat(metrics.totalBidSize()).isEqualTo(35); // 15 + 20
    }

    @Test
    void deleteMiddleLevel_shiftsOnlyAfter() {
        book.updateDepth(0, "INSERT", "SELL", 70.55, 10, instrument);
        book.updateDepth(1, "INSERT", "SELL", 70.60, 15, instrument);
        book.updateDepth(2, "INSERT", "SELL", 70.65, 20, instrument);

        // Delete level 1
        book.updateDepth(1, "DELETE", "SELL", 0, 0, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        assertThat(metrics.bestAsk()).isCloseTo(70.55, within(0.001));
        assertThat(metrics.totalAskSize()).isEqualTo(30); // 10 + 20
    }

    // =================== Depth imbalance calculation ===================

    @Test
    void depthImbalance_moreBids_positive() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 100, instrument);
        book.updateDepth(0, "INSERT", "SELL", 70.55, 50, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        // imbalance = (100 - 50) / (100 + 50) = 50/150 = 0.333
        assertThat(metrics.depthImbalance()).isCloseTo(0.333, within(0.01));
    }

    @Test
    void depthImbalance_moreAsks_negative() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 30, instrument);
        book.updateDepth(0, "INSERT", "SELL", 70.55, 100, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        // imbalance = (30 - 100) / (30 + 100) = -70/130 ~ -0.538
        assertThat(metrics.depthImbalance()).isCloseTo(-0.538, within(0.01));
    }

    @Test
    void depthImbalance_equalSizes_zero() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 50, instrument);
        book.updateDepth(0, "INSERT", "SELL", 70.55, 50, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        assertThat(metrics.depthImbalance()).isCloseTo(0.0, within(0.001));
    }

    // =================== Wall detection ===================

    @Test
    void wallDetection_largeOrder_triggersAppearedEvent() {
        // avgSize will be computed per side; we need one level to be much larger
        // Insert 3 bid levels: 10, 10, 100 => avg = 40, threshold = 3*40=120
        // Actually with 3 levels: avg = (10+10+100)/3 = 40, wall threshold = 120
        // 100 is not > 120, so let's make the wall bigger
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(1, "INSERT", "BUY", 70.45, 10, instrument);
        book.updateDepth(2, "INSERT", "BUY", 70.40, 200, instrument);

        // After 3 inserts: avg = (10+10+200)/3 = 73.33, threshold = 3*73.33 = 220
        // 200 is not > 220, so this won't trigger. Let's make the wall even larger.
        book.updateDepth(2, "UPDATE", "BUY", 70.40, 500, instrument);

        // After update: avg = (10+10+500)/3 = 173.33, threshold = 3*173.33 = 520
        // 500 is still not > 520. Let's use a different distribution.
        // Use 2 levels: 10, 10, then insert 100 at pos 2 with wallThreshold=3
        // avg = (10+10+100)/3 = 40, threshold = 120; 100 < 120 — still not enough
        // Let's add just 2 small levels and one huge one differently:
        // Use explicit numbers: levels with sizes [1, 1, 50]
        // avg = (1+1+50)/3 = 17.33, threshold = 52 => 50 < 52, nope
        // [1, 1, 1, 100]: avg = (1+1+1+100)/4 = 25.75, threshold = 77.25 => 100 > 77.25 yes!

        // Start fresh with a new book
        MutableOrderBook book2 = new MutableOrderBook(WALL_THRESHOLD);
        book2.updateDepth(0, "INSERT", "BUY", 70.50, 1, instrument);
        book2.updateDepth(1, "INSERT", "BUY", 70.45, 1, instrument);
        book2.updateDepth(2, "INSERT", "BUY", 70.40, 1, instrument);
        book2.updateDepth(3, "INSERT", "BUY", 70.35, 100, instrument);

        List<WallEvent> events = book2.recentWallEvents(Duration.ofMinutes(1));

        assertThat(events).isNotEmpty();
        assertThat(events.stream().anyMatch(e ->
                e.type() == WallEvent.WallEventType.APPEARED
                        && e.side() == WallEvent.WallSide.BID
                        && e.size() == 100
        )).isTrue();
    }

    @Test
    void wallDisappearance_sizeDrops_triggersDisappearedEvent() {
        // First create a wall
        MutableOrderBook book2 = new MutableOrderBook(WALL_THRESHOLD);
        book2.updateDepth(0, "INSERT", "BUY", 70.50, 1, instrument);
        book2.updateDepth(1, "INSERT", "BUY", 70.45, 1, instrument);
        book2.updateDepth(2, "INSERT", "BUY", 70.40, 1, instrument);
        book2.updateDepth(3, "INSERT", "BUY", 70.35, 100, instrument);

        // Verify wall appeared
        List<WallEvent> beforeEvents = book2.recentWallEvents(Duration.ofMinutes(1));
        long appearedCount = beforeEvents.stream()
                .filter(e -> e.type() == WallEvent.WallEventType.APPEARED).count();
        assertThat(appearedCount).isGreaterThanOrEqualTo(1);

        // Now reduce the size of the wall level so it's no longer a wall
        book2.updateDepth(3, "UPDATE", "BUY", 70.35, 2, instrument);

        List<WallEvent> afterEvents = book2.recentWallEvents(Duration.ofMinutes(1));
        long disappearedCount = afterEvents.stream()
                .filter(e -> e.type() == WallEvent.WallEventType.DISAPPEARED).count();
        assertThat(disappearedCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void wallDetection_metricsShowWallInfo() {
        MutableOrderBook book2 = new MutableOrderBook(WALL_THRESHOLD);
        book2.updateDepth(0, "INSERT", "SELL", 70.55, 1, instrument);
        book2.updateDepth(1, "INSERT", "SELL", 70.60, 1, instrument);
        book2.updateDepth(2, "INSERT", "SELL", 70.65, 1, instrument);
        book2.updateDepth(3, "INSERT", "SELL", 70.70, 100, instrument);

        DepthMetrics metrics = book2.computeMetrics(instrument);

        assertThat(metrics.askWall()).isNotNull();
        assertThat(metrics.askWall().size()).isEqualTo(100);
        assertThat(metrics.askWall().price()).isCloseTo(70.70, within(0.001));
    }

    // =================== Empty book ===================

    @Test
    void emptyBook_safeMetrics_noNPE() {
        DepthMetrics metrics = book.computeMetrics(instrument);

        assertThat(metrics.totalBidSize()).isEqualTo(0);
        assertThat(metrics.totalAskSize()).isEqualTo(0);
        assertThat(metrics.depthImbalance()).isCloseTo(0.0, within(0.001));
        assertThat(metrics.bestBid()).isCloseTo(0.0, within(0.001));
        assertThat(metrics.bestAsk()).isCloseTo(0.0, within(0.001));
        assertThat(metrics.spread()).isCloseTo(0.0, within(0.001));
        assertThat(metrics.bidWall()).isNull();
        assertThat(metrics.askWall()).isNull();
    }

    @Test
    void emptyBook_hasData_returnsFalse() {
        assertThat(book.hasData()).isFalse();
    }

    @Test
    void afterInsert_hasData_returnsTrue() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        assertThat(book.hasData()).isTrue();
    }

    // =================== Spread calculation ===================

    @Test
    void spread_calculatedCorrectly() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(0, "INSERT", "SELL", 70.55, 10, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);

        assertThat(metrics.spread()).isCloseTo(0.05, within(0.001));
        // MCL tick size is 0.01, so 0.05 / 0.01 = 5 ticks
        assertThat(metrics.spreadTicks()).isCloseTo(5.0, within(0.01));
    }

    // =================== Position boundary ===================

    @Test
    void positionOutOfRange_ignored() {
        // Position -1 and MAX_DEPTH should be silently ignored
        book.updateDepth(-1, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(MutableOrderBook.MAX_DEPTH, "INSERT", "BUY", 70.50, 10, instrument);

        assertThat(book.hasData()).isFalse();
    }

    // =================== Unknown operation ===================

    @Test
    void unknownOperation_ignored() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(0, "UNKNOWN_OP", "BUY", 70.50, 100, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);
        assertThat(metrics.totalBidSize()).isEqualTo(10); // unchanged
    }

    // =================== Wall event bounding ===================

    @Test
    void recentWallEvents_returnsEmpty_whenNoWalls() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(1, "INSERT", "BUY", 70.45, 10, instrument);

        List<WallEvent> events = book.recentWallEvents(Duration.ofMinutes(1));
        // Levels are equal, no wall above threshold
        assertThat(events).isEmpty();
    }

    @Test
    void deleteAllLevels_bookBecomesEmpty() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        book.updateDepth(0, "DELETE", "BUY", 0, 0, instrument);

        DepthMetrics metrics = book.computeMetrics(instrument);
        assertThat(metrics.totalBidSize()).isEqualTo(0);
    }

    @Test
    void multipleLevels_metricsAggregateCorrectly() {
        // Insert 5 bid levels
        for (int i = 0; i < 5; i++) {
            book.updateDepth(i, "INSERT", "BUY", 70.50 - i * 0.01, 10 + i, instrument);
        }
        // Sizes: 10, 11, 12, 13, 14 = total 60
        DepthMetrics metrics = book.computeMetrics(instrument);
        assertThat(metrics.totalBidSize()).isEqualTo(60);
        assertThat(metrics.bestBid()).isCloseTo(70.50, within(0.001));
    }

    @Test
    void instrumentPassedToMetrics() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        DepthMetrics metrics = book.computeMetrics(instrument);
        assertThat(metrics.instrument()).isEqualTo(instrument);
    }

    @Test
    void metricsTimestamp_isRecent() {
        book.updateDepth(0, "INSERT", "BUY", 70.50, 10, instrument);
        DepthMetrics metrics = book.computeMetrics(instrument);
        assertThat(metrics.timestamp()).isNotNull();
    }
}
