package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class APIFootballClient {

    private static final String API_KEY = System.getenv("FOOTBALL_DATA_KEY");
    private static final String BASE_URL = "https://api.football-data.org/v4/";
    private static final String PL_CODE  = "PL";

    private static JSONObject get(String endpoint) throws Exception {
        Thread.sleep(6000); // respect rate limit
        URL url = new URL(BASE_URL + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("X-Auth-Token", API_KEY);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        int status = conn.getResponseCode();
        System.out.println("[DEBUG] URL: " + BASE_URL + endpoint);
        System.out.println("[DEBUG] Status: " + status);

        if (status != 200) {
            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream()));
            StringBuilder errResponse = new StringBuilder();
            String line;
            while ((line = errReader.readLine()) != null) errResponse.append(line);
            errReader.close();
            System.out.println("[DEBUG] Error body: " + errResponse);
            throw new Exception("HTTP " + status + " for: " + endpoint);
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();

        return new JSONObject(response.toString());
    }

    public static JSONArray getUpcomingFixtures() {
        try {
            JSONObject json = get("competitions/" + PL_CODE + "/matches?status=SCHEDULED&limit=10");
            return json.getJSONArray("matches");
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch upcoming fixtures: " + e.getMessage());
            return new JSONArray();
        }
    }

    public static JSONArray getLastMatches(int teamId) {
        try {
            JSONObject json = get("teams/" + teamId + "/matches?status=FINISHED&limit=5");
            return json.getJSONArray("matches");
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch last matches for teamId=" + teamId + ": " + e.getMessage());
            return new JSONArray();
        }
    }

    public static JSONArray getHeadToHead(int homeId, int awayId) {
        try {
            JSONObject json = get("teams/" + homeId + "/matches?status=FINISHED&limit=20");
            JSONArray all = json.getJSONArray("matches");

            JSONArray h2h = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject match = all.getJSONObject(i);
                int hId = match.getJSONObject("homeTeam").getInt("id");
                int aId = match.getJSONObject("awayTeam").getInt("id");
                if ((hId == homeId && aId == awayId) || (hId == awayId && aId == homeId)) {
                    h2h.put(match);
                }
            }
            return h2h;
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch H2H: " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * Fetches the last 10 finished PL matches.
     * Used by ResultTracker to reconcile predictions with actual results.
     */
    public static JSONArray getRecentResults() {
        try {
            JSONObject json = get("competitions/" + PL_CODE + "/matches?status=FINISHED&limit=10");
            return json.getJSONArray("matches");
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch recent results: " + e.getMessage());
            return new JSONArray();
        }
    }
}