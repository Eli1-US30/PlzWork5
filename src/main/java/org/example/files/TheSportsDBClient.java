package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Replaces the hardcoded historical standings with live data
 * fetched from football-data.org.
 *
 * Fetches the last 5 completed PL seasons and calculates each
 * team's average finishing position. Teams not in PL that season
 * are excluded from the average (same logic as before).
 *
 * Seasons fetched: 2020, 2021, 2022, 2023, 2024
 * Each costs one API call with a 6 second sleep between them.
 *
 * ELO bonus formula (unchanged):
 *   1st place avg  = +200 ELO
 *   10th place avg = +100 ELO
 *   20th place avg =    0 ELO
 */
public class TheSportsDBClient {

    private static final String API_KEY  = System.getenv("FOOTBALL_DATA_KEY");
    private static final String BASE_URL = "https://api.football-data.org/v4/";

    // Seasons to fetch — update this list each summer
    private static final int[] SEASONS = {2020, 2021, 2022, 2023, 2024};

    // team name -> list of finishing positions (one per season they were in PL)
    private static final Map<String, java.util.List<Integer>> positionHistory
            = new HashMap<>();

    // team name -> calculated average position
    private static final Map<String, Double> avgPositions = new HashMap<>();

    private static boolean loaded = false;

    /**
     * Fetches historical standings from football-data.org.
     * Called once at startup. Takes ~30 seconds due to rate limit sleeps.
     */
    public static void preload() {
        if (loaded) return;

        System.out.println("[HISTORY] Fetching last " + SEASONS.length
                + " seasons of PL standings...");

        for (int season : SEASONS) {
            try {
                Thread.sleep(6000); // respect 10 req/min rate limit

                String endpoint = "competitions/PL/standings?season=" + season;
                URL url = new URL(BASE_URL + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("X-Auth-Token", API_KEY);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    System.out.println("[HISTORY] Could not fetch season "
                            + season + " — HTTP " + status);
                    continue;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json  = new JSONObject(sb.toString());
                JSONArray  table = json
                        .getJSONArray("standings")
                        .getJSONObject(0)
                        .getJSONArray("table");

                for (int i = 0; i < table.length(); i++) {
                    JSONObject row  = table.getJSONObject(i);
                    int    position = row.getInt("position");
                    String teamName = row.getJSONObject("team").getString("name");

                    positionHistory
                            .computeIfAbsent(teamName, k -> new java.util.ArrayList<>())
                            .add(position);
                }

                System.out.println("[HISTORY] Season " + season + " loaded ("
                        + table.length() + " teams)");

            } catch (Exception e) {
                System.out.println("[HISTORY] Error fetching season "
                        + season + ": " + e.getMessage());
            }
        }

        // Calculate average positions
        for (Map.Entry<String, java.util.List<Integer>> entry
                : positionHistory.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(20.0);
            avgPositions.put(entry.getKey(), avg);
            System.out.printf("[HISTORY] %-35s avg pos=%.1f  bonus=%.0f%n",
                    entry.getKey(), avg, calculateBonus(avg));
        }

        System.out.println("[HISTORY] Historical standings loaded for "
                + avgPositions.size() + " teams");
        loaded = true;
    }

    /**
     * Returns ELO bonus based on average finishing position.
     * 1st  = +200, 10th = +100, 20th = 0
     */
    public static double getHistoricalEloBonus(String fdTeamName) {
        Double avgPos = avgPositions.get(fdTeamName);

        if (avgPos == null) {
            // Newly promoted team with no PL history
            System.out.println("[HISTORY] No data for: " + fdTeamName
                    + " — using 0 bonus");
            return 0;
        }

        double bonus = calculateBonus(avgPos);
        System.out.printf("[HISTORY] %s avgPos=%.1f eloBonus=%.0f%n",
                fdTeamName, avgPos, bonus);
        return bonus;
    }

    private static double calculateBonus(double avgPos) {
        return Math.max(0, (21 - avgPos) * (200.0 / 20.0));
    }
}