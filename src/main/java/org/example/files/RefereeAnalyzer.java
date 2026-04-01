package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/**
 * Tracks referee statistics across the season and applies
 * two types of nudges to predictions:
 *
 * 1. OVER/UNDER NUDGE — based on referee's avg goals per game.
 *    Only applied after 5+ games officiated this season.
 *    A high-scoring ref (avg 3.2 goals) nudges lambdas up slightly.
 *    A low-scoring ref (avg 2.0 goals) nudges lambdas down.
 *
 * 2. MATCH WINNER NUDGE — only applied if the ref has officiated
 *    5+ games involving a specific team AND shows a clear trend
 *    (>65% win rate for that team in those games).
 *    This catches referees who consistently make decisions that
 *    benefit or disadvantage particular teams.
 *
 * Data is stored in data/referee_stats.json and built up
 * automatically by ResultTracker after each match is reconciled.
 *
 * League average: ~2.7 goals per PL game (2024/25 season)
 */
public class RefereeAnalyzer {

    private static final String FILE_PATH          = "data/referee_stats.json";
    private static final int    MIN_GAMES          = 5;      // minimum before any adjustment
    private static final double LEAGUE_AVG_GOALS   = 2.7;
    private static final double MAX_LAMBDA_NUDGE   = 0.15;   // max ±15% on lambda
    private static final double TREND_THRESHOLD    = 0.65;   // 65% win rate = clear trend
    private static final double MAX_WINNER_NUDGE   = 0.08;   // max ±8% match winner nudge

    // Loaded at startup — referee name -> stats
    private static final Map<String, RefereeStats> stats = new HashMap<>();
    private static boolean loaded = false;

    // -------------------------------------------------------
    // Data class for one referee's season stats
    // -------------------------------------------------------
    public static class RefereeStats {
        public int    gamesOfficiated = 0;
        public double totalGoals      = 0;

        // teamName -> [wins, total] in games this ref officiated
        public final Map<String, int[]> teamRecord = new HashMap<>();

        public double avgGoalsPerGame() {
            return gamesOfficiated > 0 ? totalGoals / gamesOfficiated : LEAGUE_AVG_GOALS;
        }
    }

    // -------------------------------------------------------
    // Load stats from JSON file at startup
    // -------------------------------------------------------
    public static void load() {
        if (loaded) return;
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            System.out.println("[REF] No referee stats file yet — will build over time");
            loaded = true;
            return;
        }

        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);

            JSONObject root = new JSONObject(sb.toString());
            for (String refName : root.keySet()) {
                JSONObject refData = root.getJSONObject(refName);
                RefereeStats rs    = new RefereeStats();
                rs.gamesOfficiated = refData.optInt("gamesOfficiated", 0);
                rs.totalGoals      = refData.optDouble("totalGoals", 0);

                JSONObject teamRec = refData.optJSONObject("teamRecord");
                if (teamRec != null) {
                    for (String teamName : teamRec.keySet()) {
                        JSONArray arr = teamRec.getJSONArray(teamName);
                        rs.teamRecord.put(teamName, new int[]{arr.getInt(0), arr.getInt(1)});
                    }
                }
                stats.put(refName, rs);
            }

            System.out.println("[REF] Loaded stats for " + stats.size() + " referees");
        } catch (Exception e) {
            System.out.println("[REF] Error loading referee stats: " + e.getMessage());
        }
        loaded = true;
    }

    // -------------------------------------------------------
    // Record a match result — called by ResultTracker
    // -------------------------------------------------------
    public static void recordMatch(String refereeName, String homeTeam, String awayTeam,
                                   int homeGoals, int awayGoals) {
        if (refereeName == null || refereeName.isBlank()) return;

        RefereeStats rs = stats.computeIfAbsent(refereeName, k -> new RefereeStats());
        rs.gamesOfficiated++;
        rs.totalGoals += homeGoals + awayGoals;

        // Determine winner
        String winner = homeGoals > awayGoals ? homeTeam
                : awayGoals > homeGoals ? awayTeam : null; // null = draw

        // Update team records for both teams
        for (String team : new String[]{homeTeam, awayTeam}) {
            rs.teamRecord.computeIfAbsent(team, k -> new int[]{0, 0});
            rs.teamRecord.get(team)[1]++; // total games with this ref
            if (team.equals(winner)) {
                rs.teamRecord.get(team)[0]++; // wins
            }
        }

        save();
        System.out.printf("[REF] Recorded: %s officiates %s %d-%d %s%n",
                refereeName, homeTeam, homeGoals, awayGoals, awayTeam);
    }

    // -------------------------------------------------------
    // Get lambda multiplier for over/under based on referee
    // Returns a multiplier to apply to BOTH lambdas
    // e.g. 1.05 = nudge total goals up 5%, 0.95 = nudge down 5%
    // Returns 1.0 if not enough data
    // -------------------------------------------------------
    public static double getLambdaMultiplier(String refereeName) {
        if (refereeName == null || refereeName.isBlank()) return 1.0;

        RefereeStats rs = stats.get(refereeName);
        if (rs == null || rs.gamesOfficiated < MIN_GAMES) {
            System.out.printf("[REF] %s — insufficient data (%d games) — no adjustment%n",
                    refereeName, rs != null ? rs.gamesOfficiated : 0);
            return 1.0;
        }

        double avgGoals = rs.avgGoalsPerGame();
        double diff     = avgGoals - LEAGUE_AVG_GOALS;

        // Scale: each 0.5 goals above/below average = ~5% nudge, capped at 15%
        double nudge = Math.max(-MAX_LAMBDA_NUDGE,
                Math.min(MAX_LAMBDA_NUDGE, diff * 0.10));

        System.out.printf("[REF] %s avgGoals=%.2f leagueAvg=%.2f → lambda nudge=%.1f%%%n",
                refereeName, avgGoals, LEAGUE_AVG_GOALS, nudge * 100);

        return 1.0 + nudge;
    }

    // -------------------------------------------------------
    // Get home lambda multiplier based on referee team bias
    // Returns a multiplier for home lambda only
    // e.g. 1.06 = home team gets a 6% boost (ref favours them historically)
    // Returns 1.0 if not enough data or no clear trend
    // -------------------------------------------------------
    public static double getHomeTeamBiasMultiplier(String refereeName,
                                                   String homeTeam,
                                                   String awayTeam) {
        if (refereeName == null || refereeName.isBlank()) return 1.0;

        RefereeStats rs = stats.get(refereeName);
        if (rs == null || rs.gamesOfficiated < MIN_GAMES) return 1.0;

        // Check home team record with this ref
        double homeNudge = getTeamBiasNudge(rs, homeTeam);
        // Check away team record with this ref (inverted — if ref favours away, hurts home)
        double awayNudge = getTeamBiasNudge(rs, awayTeam);

        double combined = homeNudge - awayNudge;
        combined = Math.max(-MAX_WINNER_NUDGE, Math.min(MAX_WINNER_NUDGE, combined));

        if (Math.abs(combined) > 0.01) {
            System.out.printf("[REF] %s team bias: %s nudge=%.1f%%  %s nudge=%.1f%%  " +
                            "combined home adj=%.1f%%%n",
                    refereeName, homeTeam, homeNudge * 100,
                    awayTeam, awayNudge * 100, combined * 100);
        }

        return 1.0 + combined;
    }

    private static double getTeamBiasNudge(RefereeStats rs, String teamName) {
        int[] record = rs.teamRecord.get(teamName);
        if (record == null || record[1] < MIN_GAMES) return 0.0;

        double winRate = (double) record[0] / record[1];

        // Only nudge if there's a clear trend
        if (winRate >= TREND_THRESHOLD) {
            return MAX_WINNER_NUDGE * (winRate - 0.5) / 0.5;
        } else if (winRate <= (1.0 - TREND_THRESHOLD)) {
            return -MAX_WINNER_NUDGE * (0.5 - winRate) / 0.5;
        }
        return 0.0;
    }

    // -------------------------------------------------------
    // Save stats to JSON file
    // -------------------------------------------------------
    private static void save() {
        try {
            new File("data").mkdirs();
            JSONObject root = new JSONObject();

            for (Map.Entry<String, RefereeStats> entry : stats.entrySet()) {
                RefereeStats rs  = entry.getValue();
                JSONObject refData = new JSONObject();
                refData.put("gamesOfficiated", rs.gamesOfficiated);
                refData.put("totalGoals",      rs.totalGoals);

                JSONObject teamRec = new JSONObject();
                for (Map.Entry<String, int[]> te : rs.teamRecord.entrySet()) {
                    JSONArray arr = new JSONArray();
                    arr.put(te.getValue()[0]);
                    arr.put(te.getValue()[1]);
                    teamRec.put(te.getKey(), arr);
                }
                refData.put("teamRecord", teamRec);
                root.put(entry.getKey(), refData);
            }

            try (FileWriter fw = new FileWriter(FILE_PATH)) {
                fw.write(root.toString(2));
            }
        } catch (Exception e) {
            System.out.println("[REF] Error saving referee stats: " + e.getMessage());
        }
    }

    // -------------------------------------------------------
    // Get referee name from a fixture JSON object
    // Returns null if not assigned yet
    // -------------------------------------------------------
    public static String getRefereeFromFixture(JSONObject fixture) {
        try {
            return fixture.optString("referees", null) != null
                    ? null // fallback — referees field is an array
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts referee name from football-data.org fixture JSON.
     * The referees field is a JSON array of objects with a "name" field.
     * Returns the first referee's name, or null if not assigned.
     */
    public static String extractReferee(JSONObject fixture) {
        try {
            JSONArray refs = fixture.optJSONArray("referees");
            if (refs == null || refs.isEmpty()) return null;
            return refs.getJSONObject(0).optString("name", null);
        } catch (Exception e) {
            return null;
        }
    }
}