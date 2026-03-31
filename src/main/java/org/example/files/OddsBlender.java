package org.example.files;

/**
 * Blends our model's probabilities with fair bookmaker probabilities
 * to produce more accurate final predictions.
 *
 * WHY THIS WORKS:
 *   Bookmakers employ large teams of analysts, receive early injury news,
 *   and their odds shift based on sharp bettor activity. Their implied
 *   probabilities carry information our model doesn't have.
 *
 *   Our model is strong on:
 *     - Historical ELO and long-run quality
 *     - xG-based attack/defense ratings
 *     - Home/away splits and form
 *
 *   Bookmakers are strong on:
 *     - Late team news and lineup information
 *     - Short-term form signals from sharp money
 *     - Over/under goal lines (one of their sharpest markets)
 *
 * BLEND WEIGHTS:
 *   Match winner: 60% our model, 40% bookmaker
 *   Over/under:   50% our model, 50% bookmaker
 *   (over/under given equal weight because bookmaker totals lines
 *    are particularly sharp and our lambda already captures goals well)
 *
 * If no odds are available the original model probabilities are returned
 * unchanged — the blender degrades gracefully.
 */
public class OddsBlender {

    // How much weight to give the bookmaker's fair probability
    private static final double BOOKIE_WEIGHT_MATCH    = 0.40;
    private static final double BOOKIE_WEIGHT_TOTALS   = 0.50;
    private static final double MODEL_WEIGHT_MATCH     = 1.0 - BOOKIE_WEIGHT_MATCH;
    private static final double MODEL_WEIGHT_TOTALS    = 1.0 - BOOKIE_WEIGHT_TOTALS;

    /**
     * Blended result for a single match — replaces raw model probabilities.
     */
    public static class BlendedProbabilities {
        // Match winner (sum to 100)
        public final double homeWinProb;
        public final double drawProb;
        public final double awayWinProb;

        // Over/under (each pair sums to 100)
        public final double over15Prob;
        public final double over25Prob;
        public final double over35Prob;

        // Whether odds were available for blending
        public final boolean oddsUsed;
        public final String  bookmaker;

        public BlendedProbabilities(double homeWin, double draw, double awayWin,
                                    double over15, double over25, double over35,
                                    boolean oddsUsed, String bookmaker) {
            this.homeWinProb = homeWin;
            this.drawProb    = draw;
            this.awayWinProb = awayWin;
            this.over15Prob  = over15;
            this.over25Prob  = over25;
            this.over35Prob  = over35;
            this.oddsUsed    = oddsUsed;
            this.bookmaker   = bookmaker;
        }
    }

    /**
     * Blend model probabilities with bookmaker fair probabilities.
     *
     * @param homeTeam    home team name (for odds lookup)
     * @param awayTeam    away team name
     * @param modelHome   model's home win probability (0-100)
     * @param modelDraw   model's draw probability (0-100)
     * @param modelAway   model's away win probability (0-100)
     * @param modelOver15 model's over 1.5 goals probability (0-100)
     * @param modelOver25 model's over 2.5 goals probability (0-100)
     * @param modelOver35 model's over 3.5 goals probability (0-100)
     */
    public static BlendedProbabilities blend(
            String homeTeam, String awayTeam,
            double modelHome, double modelDraw, double modelAway,
            double modelOver15, double modelOver25, double modelOver35) {

        OddsClient.OddsData odds = OddsClient.getOdds(homeTeam, awayTeam);

        if (odds == null || !odds.hasMatchOdds()) {
            System.out.println("[BLEND] No odds available for "
                    + homeTeam + " vs " + awayTeam + " — using model only");
            return new BlendedProbabilities(
                    modelHome, modelDraw, modelAway,
                    modelOver15, modelOver25, modelOver35,
                    false, "none");
        }

        // --- Blend match winner probabilities ---
        double blendedHome = blend(modelHome, odds.fairHomeWinProb, MODEL_WEIGHT_MATCH, BOOKIE_WEIGHT_MATCH);
        double blendedDraw = blend(modelDraw, odds.fairDrawProb,    MODEL_WEIGHT_MATCH, BOOKIE_WEIGHT_MATCH);
        double blendedAway = blend(modelAway, odds.fairAwayWinProb, MODEL_WEIGHT_MATCH, BOOKIE_WEIGHT_MATCH);

        // Renormalise match winner to ensure they sum to exactly 100%
        double matchTotal  = blendedHome + blendedDraw + blendedAway;
        blendedHome = (blendedHome / matchTotal) * 100.0;
        blendedDraw = (blendedDraw / matchTotal) * 100.0;
        blendedAway = (blendedAway / matchTotal) * 100.0;

        // --- Blend over/under probabilities ---
        double blendedOver15 = blendOverUnder(modelOver15, odds, 1.5);
        double blendedOver25 = blendOverUnder(modelOver25, odds, 2.5);
        double blendedOver35 = blendOverUnder(modelOver35, odds, 3.5);

        System.out.printf("[BLEND] %s vs %s (bookie: %s overround=%.1f%%)%n",
                homeTeam, awayTeam, odds.bookmaker, odds.overround);
        System.out.printf("[BLEND] Match winner  model: H=%.1f%% D=%.1f%% A=%.1f%%  " +
                        "bookie: H=%.1f%% D=%.1f%% A=%.1f%%  " +
                        "blended: H=%.1f%% D=%.1f%% A=%.1f%%%n",
                modelHome, modelDraw, modelAway,
                odds.fairHomeWinProb, odds.fairDrawProb, odds.fairAwayWinProb,
                blendedHome, blendedDraw, blendedAway);
        System.out.printf("[BLEND] Over 2.5  model=%.1f%%  bookie=%.1f%%  blended=%.1f%%%n",
                modelOver25,
                odds.fairOverProb.getOrDefault(2.5, -1.0),
                blendedOver25);

        return new BlendedProbabilities(
                blendedHome, blendedDraw, blendedAway,
                blendedOver15, blendedOver25, blendedOver35,
                true, odds.bookmaker);
    }

    /**
     * Blend a single over/under line.
     * Falls back to model value if bookie doesn't have this line.
     */
    private static double blendOverUnder(double modelProb,
                                         OddsClient.OddsData odds,
                                         double line) {
        if (!odds.hasOverUnder(line)) return modelProb;

        double bookieProb = odds.fairOverProb.get(line);
        double blended    = blend(modelProb, bookieProb,
                MODEL_WEIGHT_TOTALS, BOOKIE_WEIGHT_TOTALS);

        // Clamp to [1, 99] — never be 100% certain either way
        return Math.max(1.0, Math.min(99.0, blended));
    }

    /** Weighted blend of two probability values */
    private static double blend(double modelVal, double bookieVal,
                                double modelWeight, double bookieWeight) {
        return (modelVal * modelWeight) + (bookieVal * bookieWeight);
    }
}