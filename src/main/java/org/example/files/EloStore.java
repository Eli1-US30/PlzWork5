package org.example.files;

import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;

/**
 * Persists ELO ratings to a local JSON file so they evolve over time.
 *
 * Instead of always starting from hardcoded seed values, this class:
 *   1. On startup: loads previously saved ELO ratings from elo_ratings.json
 *   2. During prediction: provides current ELO for each team
 *   3. After a result: updates both teams' ELO using the standard formula
 *      and saves back to the file immediately
 *
 * If no file exists yet (first run), falls back to EloRatingSystem.seedElo()
 * so the system works out of the box with no setup required.
 *
 * File location: ./data/elo_ratings.json (created automatically)
 */
public class EloStore {

    private static final String DATA_DIR  = "data";
    private static final String FILE_PATH = DATA_DIR + "/elo_ratings.json";

    // In-memory cache of current ELO ratings
    private static JSONObject ratings = null;

    /**
     * Load ratings from file on startup.
     * If file doesn't exist, starts fresh from seed values.
     */
    public static void load() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(FILE_PATH);
        if (!file.exists()) {
            System.out.println("[ELO-STORE] No existing ratings file — starting from seed values");
            ratings = new JSONObject();
            return;
        }

        try {
            String content = new String(Files.readAllBytes(Paths.get(FILE_PATH)));
            ratings = new JSONObject(content);
            System.out.println("[ELO-STORE] Loaded ratings for " + ratings.length() + " teams from " + FILE_PATH);
        } catch (Exception e) {
            System.out.println("[ELO-STORE] Failed to load ratings file: " + e.getMessage() + " — using seeds");
            ratings = new JSONObject();
        }
    }

    /**
     * Get current ELO for a team.
     * Falls back to seed value if team not yet in the store.
     */
    public static double getElo(String teamName) {
        if (ratings == null) load();

        if (ratings.has(teamName)) {
            return ratings.getDouble(teamName);
        }

        // First time we've seen this team — use seed
        double seed = EloRatingSystem.seedElo(teamName);
        ratings.put(teamName, seed);
        return seed;
    }

    /**
     * Update ELO for both teams after a match result and save to file.
     *
     * @param homeTeam   home team name
     * @param awayTeam   away team name
     * @param homeGoals  actual home goals scored
     * @param awayGoals  actual away goals scored
     */
    public static void recordResult(String homeTeam, String awayTeam,
                                    int homeGoals, int awayGoals) {
        if (ratings == null) load();

        double homeElo = getElo(homeTeam);
        double awayElo = getElo(awayTeam);

        // Convert result to ELO score (1.0 win, 0.5 draw, 0.0 loss)
        double homeResult, awayResult;
        if (homeGoals > awayGoals) {
            homeResult = 1.0;
            awayResult = 0.0;
        } else if (awayGoals > homeGoals) {
            homeResult = 0.0;
            awayResult = 1.0;
        } else {
            homeResult = 0.5;
            awayResult = 0.5;
        }

        double newHomeElo = EloRatingSystem.update(homeElo, awayElo, homeResult);
        double newAwayElo = EloRatingSystem.update(awayElo, homeElo, awayResult);

        ratings.put(homeTeam, Math.round(newHomeElo * 10.0) / 10.0);
        ratings.put(awayTeam, Math.round(newAwayElo * 10.0) / 10.0);

        System.out.printf("[ELO-STORE] %s %.0f→%.0f  |  %s %.0f→%.0f%n",
                homeTeam, homeElo, newHomeElo,
                awayTeam, awayElo, newAwayElo);

        save();
    }

    /**
     * Save current ratings to file.
     */
    private static void save() {
        try (FileWriter writer = new FileWriter(FILE_PATH)) {
            writer.write(ratings.toString(2)); // pretty-print with 2-space indent
            System.out.println("[ELO-STORE] Ratings saved to " + FILE_PATH);
        } catch (Exception e) {
            System.out.println("[ELO-STORE] Failed to save ratings: " + e.getMessage());
        }
    }

    /**
     * Print current standings sorted by ELO (highest first).
     * Useful to call at end of each run to see how ratings have evolved.
     */
    public static void printStandings() {
        if (ratings == null || ratings.length() == 0) {
            System.out.println("[ELO-STORE] No ratings loaded yet.");
            return;
        }

        System.out.println("\n=== Current ELO Ratings ===");
        ratings.keySet().stream()
                .sorted((a, b) -> Double.compare(ratings.getDouble(b), ratings.getDouble(a)))
                .forEach(team -> System.out.printf("  %-35s %.0f%n", team, ratings.getDouble(team)));
        System.out.println("===========================\n");
    }
}