package com.riskdesk.domain.orderflow.perfectsetup;

/**
 * Nearest resting iceberg on each side of the book, as seen by the Perfect
 * Setup detector. A BID iceberg is hidden demand (support); an ASK iceberg is
 * hidden supply (resistance). Levels are {@code null} when no recent iceberg of
 * that side exists.
 *
 * @param bidLevel     price of the nearest BID iceberg, or {@code null}
 * @param bidScore     its detection score (0-100)
 * @param bidRecharges its recharge count
 * @param askLevel     price of the nearest ASK iceberg, or {@code null}
 * @param askScore     its detection score (0-100)
 * @param askRecharges its recharge count
 */
public record IcebergContext(
    Double bidLevel,
    double bidScore,
    int bidRecharges,
    Double askLevel,
    double askScore,
    int askRecharges
) {
    public static IcebergContext empty() {
        return new IcebergContext(null, 0.0, 0, null, 0.0, 0);
    }

    public boolean hasBid() {
        return bidLevel != null;
    }

    public boolean hasAsk() {
        return askLevel != null;
    }
}
