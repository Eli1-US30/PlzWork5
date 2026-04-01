package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Analyses historical head-to-head results between two teams.
 *
 * New in this version (feature 4):
 * - Tracks historical H2H scorelines and their frequency
 * - getScorelineWeights() returns a map of "homeGoals-awayGoals" -> weight
 *   which Simulator uses to bias the Monte Carlo distribution
 *   toward historically common scorelines between these two teams
 * - A scoreline that has occurred 3+ times in H2H history gets
 *   extra weight in the simulation
 *
 * The scoreline weighting is blended lightly (20%) so it nudges
 * the distribution without overriding the Poisson model entirely.
 * This is especially useful for "boring" H2H fixtures that
 * consistently produce 0-0 or 1-0 results.
 */
public class HeadToHeadAnalyzer {

    // Minimum occurrences before a scoreline gets extra weight
    private static final int    MIN_SCORELINE_COUNT = 2;
    // How much the H2H scoreline distribution blends into Monte Carlo
    private static final double SCORELINE_BLEND     = 0.20;

    /**
     * Returns a win/loss advantage factor for the home team.
     * Positive = home team has historically dominated this matchup.
     * Range: roughly -0.2 to +0.2
     */
    public static double getAdvantage(int homeId, int awayId) {
        int homeWins = 0;
        int awayWins = 0;

        try {
            JSONArray matches = APIFootballClient.getHeadToHead(homeId, awayId);
            if (matches == null || matches.isEmpty()) return 0;

            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.getJSONObject(i);
                int hId = match.getJSONObject("homeTeam").getInt("id");

                JSONObject fullTime = match.getJSONObject("score")
                        .getJSONObject("fullTime");
                int homeGoals = fullTime.optInt("home", 0);
                int awayGoals = fullTime.optInt("away", 0);

                boolean homeWasHome = (hId == homeId);
                int teamGoals = homeWasHome ? homeGoals : awayGoals;
                int oppGoals  = homeWasHome ? awayGoals : homeGoals;

                if (teamGoals > oppGoals)      homeWins++;
                else if (oppGoals > teamGoals) awayWins++;
            }

        } catch (Exception e) {
            System.out.println("[H2H] Lookup failed — using 0 advantage");
            return 0;
        }

        int total = homeWins + awayWins;
        if (total == 0) return 0;

        double advantage = 0.2 * (homeWins - awayWins) / (double) total;
        System.out.printf("[H2H] homeWins=%d awayWins=%d advantage=%.3f%n",
                homeWins, awayWins, advantage);
        return advantage;
    }

    /**
     * Returns a map of scoreline -> normalised weight based on H2H history.
     * Key format: "homeGoals-awayGoals" from the perspective of the home team
     * in today's match (regardless of who was home in the historical match).
     *
     * Only scorelines that appeared MIN_SCORELINE_COUNT+ times are included.
     * Returns empty map if not enough H2H history.
     *
     * Used by Simulator to apply a 20% blend toward historically
     * common scorelines between these specific teams.
     *
     * @param homeId  today's home team ID
     * @param awayId  today's away team ID
     */
    public static Map<String, Double> getScorelineWeights(int homeId, int awayId) {
        Map<String, Integer> scoreCounts = new HashMap<>();
        int totalMatches = 0;

        try {
            JSONArray matches = APIFootballClient.getHeadToHead(homeId, awayId);
            if (matches == null || matches.isEmpty()) return new HashMap<>();

            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.getJSONObject(i);
                int hId = match.getJSONObject("homeTeam").getInt("id");

                JSONObject fullTime = match.getJSONObject("score")
                        .getJSONObject("fullTime");
                int homeGoals = fullTime.optInt("home", 0);
                int awayGoals = fullTime.optInt("away", 0);

                // Normalise perspective — always from today's home team's view
                int fromHomeGoals, fromAwayGoals;
                if (hId == homeId) {
                    fromHomeGoals = homeGoals;
                    fromAwayGoals = awayGoals;
                } else {
                    // The teams were swapped — flip the scoreline
                    fromHomeGoals = awayGoals;
                    fromAwayGoals = homeGoals;
                }

                String key = fromHomeGoals + "-" + fromAwayGoals;
                scoreCounts.merge(key, 1, Integer::sum);
                totalMatches++;
            }

        } catch (Exception e) {
            System.out.println("[H2H] Scoreline fetch failed: " + e.getMessage());
            return new HashMap<>();
        }

        if (totalMatches < MIN_SCORELINE_COUNT) return new HashMap<>();

        // Only keep scorelines that occurred multiple times
        Map<String, Double> weights = new HashMap<>();
        int repeatedTotal = 0;

        for (Map.Entry<String, Integer> entry : scoreCounts.entrySet()) {
            if (entry.getValue() >= MIN_SCORELINE_COUNT) {
                weights.put(entry.getKey(), (double) entry.getValue());
                repeatedTotal += entry.getValue();
            }
        }

        if (weights.isEmpty() || repeatedTotal == 0) return new HashMap<>();

        // Normalise to sum to 1.0
        int finalTotal = repeatedTotal;
        weights.replaceAll((k, v) -> v / finalTotal);

        System.out.printf("[H2H] Scoreline weights (%d total matches):%n", totalMatches);
        weights.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %s → %.1f%%%n",
                        e.getKey(), e.getValue() * 100));

        return weights;
    }

    /**
     * Returns the scoreline blend weight (how much H2H scorelines
     * influence the Monte Carlo simulation).
     */
    public static double getScorelineBlend() {
        return SCORELINE_BLEND;
    }
}