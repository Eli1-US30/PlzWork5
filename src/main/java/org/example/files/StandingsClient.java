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
 * Updated for 2025/26 season.
 *
 * Current position → ELO adjustment:
 *   1st  = +150
 *   10th =    0
 *   20th = -150
 */
public class StandingsClient {

    private static final String API_KEY  = System.getenv("FOOTBALL_DATA_KEY");
    private static final String BASE_URL = "https://api.football-data.org/v4/";

    private static final Map<String, Integer> currentPosition = new HashMap<>();
    private static boolean loaded = false;

    public static void preload() {
        if (loaded) return;

        try {
            Thread.sleep(6000);
            URL url = new URL(BASE_URL + "competitions/PL/standings");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("X-Auth-Token", API_KEY);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int status = conn.getResponseCode();
            System.out.println("[STANDINGS] HTTP status: " + status);

            if (status != 200) {
                System.out.println("[STANDINGS] Failed — ELO adjustments skipped");
                loaded = true;
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray table = new JSONObject(sb.toString())
                    .getJSONArray("standings")
                    .getJSONObject(0)
                    .getJSONArray("table");

            for (int i = 0; i < table.length(); i++) {
                JSONObject row  = table.getJSONObject(i);
                int    position = row.getInt("position");
                String teamName = row.getJSONObject("team").getString("name");
                currentPosition.put(teamName, position);
                System.out.printf("[STANDINGS] %2d. %s%n", position, teamName);
            }

            System.out.println("[STANDINGS] Live table loaded — "
                    + currentPosition.size() + " teams");

        } catch (Exception e) {
            System.out.println("[STANDINGS] Error: " + e.getMessage());
        }

        loaded = true;
    }

    public static double getStandingsEloAdjustment(String fdTeamName) {
        Integer pos = currentPosition.get(fdTeamName);

        if (pos == null) {
            System.out.println("[STANDINGS] No position for: " + fdTeamName
                    + " — using 0");
            return 0;
        }

        double adjustment = (10.0 - pos) * 15.0;
        System.out.printf("[STANDINGS] %s pos=%d → adj=%.0f%n",
                fdTeamName, pos, adjustment);
        return adjustment;
    }

    public static int getCurrentPosition(String fdTeamName) {
        return currentPosition.getOrDefault(fdTeamName, -1);
    }
}