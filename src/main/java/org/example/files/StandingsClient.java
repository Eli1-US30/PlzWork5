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
 * Fetches live Premier League standings from football-data.org.
 *
 * Current position is converted into an ELO adjustment:
 *   1st  = +150
 *  10th  =    0
 *  20th  = -150
 *
 * This is blended 50/50 with the seed+historical ELO so that:
 *  - A team like Forest (7th this season despite low seed) gets a meaningful bump
 *  - A team like Spurs (17th this season despite high seed) gets pulled down
 *  - Historical pedigree still counts (50%) so promoted yo-yo clubs aren't overrated
 */
public class StandingsClient {

    private static final String API_KEY  = "4f5e820f73964505baf4ad83d73104f8";
    private static final String BASE_URL = "https://api.football-data.org/v4/";

    // football-data.org team name -> current league position (1-20)
    private static final Map<String, Integer> currentPosition = new HashMap<>();
    private static boolean loaded = false;

    /**
     * Call once at startup. Fetches the live PL table and caches positions.
     */
    public static void preload() {
        if (loaded) return;

        try {
            Thread.sleep(6000); // respect rate limit
            URL url = new URL(BASE_URL + "competitions/PL/standings");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("X-Auth-Token", API_KEY);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int status = conn.getResponseCode();
            System.out.println("[STANDINGS] HTTP status: " + status);

            if (status != 200) {
                System.out.println("[STANDINGS] Failed to fetch standings — ELO adjustments will be skipped");
                loaded = true; // don't retry; fall back gracefully
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject json = new JSONObject(sb.toString());

            // The standings array has three groups: TOTAL, HOME, AWAY
            // We want TOTAL (index 0)
            JSONArray table = json
                    .getJSONArray("standings")
                    .getJSONObject(0)   // TOTAL standings
                    .getJSONArray("table");

            for (int i = 0; i < table.length(); i++) {
                JSONObject row  = table.getJSONObject(i);
                int position    = row.getInt("position");
                String teamName = row.getJSONObject("team").getString("name");
                currentPosition.put(teamName, position);
                System.out.printf("[STANDINGS] %2d. %s%n", position, teamName);
            }

            System.out.println("[STANDINGS] Live table loaded — " + currentPosition.size() + " teams");

        } catch (Exception e) {
            System.out.println("[STANDINGS] Error loading standings: " + e.getMessage());
        }

        loaded = true;
    }

    /**
     * Returns an ELO adjustment based on current league position.
     *
     * 1st  = +150
     * 10th =    0
     * 20th = -150
     *
     * Linear: adjustment = (10 - position) * 15
     */
    public static double getStandingsEloAdjustment(String fdTeamName) {
        Integer pos = currentPosition.get(fdTeamName);

        if (pos == null) {
            System.out.println("[STANDINGS] No current position found for: " + fdTeamName + " — using 0 adjustment");
            return 0;
        }

        double adjustment = (10.0 - pos) * 15.0;
        System.out.printf("[STANDINGS] %s position=%d → ELO adjustment=%.0f%n",
                fdTeamName, pos, adjustment);
        return adjustment;
    }

    /**
     * Returns the team's current league position, or -1 if unknown.
     */
    public static int getCurrentPosition(String fdTeamName) {
        return currentPosition.getOrDefault(fdTeamName, -1);
    }
}