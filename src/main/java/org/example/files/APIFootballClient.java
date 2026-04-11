package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class APIFootballClient {

    private static final String API_KEY  = System.getenv("FOOTBALL_DATA_KEY");
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
            JSONObject json = get("competitions/" + PL_CODE
                    + "/matches?status=SCHEDULED&limit=10");
            return json.getJSONArray("matches");
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch upcoming fixtures: " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * Fetches scheduled PL matches between two dates (inclusive).
     * Used by morning run to get only tomorrow's matches.
     * football-data.org supports dateFrom and dateTo query params.
     *
     * @param from start date (inclusive)
     * @param to   end date (inclusive)
     */
    public static JSONArray getFixturesForDateRange(LocalDate from, LocalDate to) {
        try {
            String endpoint = "competitions/" + PL_CODE
                    + "/matches?status=SCHEDULED"
                    + "&dateFrom=" + from.toString()
                    + "&dateTo=" + to.toString();
            JSONObject json = get(endpoint);
            JSONArray matches = json.getJSONArray("matches");
            System.out.println("[FIXTURES] Fetched " + matches.length()
                    + " match(es) between " + from + " and " + to);
            return matches;
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch fixtures for date range: "
                    + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * Fetches last 5 finished matches for a team across ALL competitions.
     * Using limit=5 with no competition filter so Europa, FA Cup etc are included.
     * This is important for fatigue calculation — a Thursday Europa game
     * affects Sunday PL performance.
     */
    public static JSONArray getLastMatches(int teamId) {
        try {
            JSONObject json = get("teams/" + teamId
                    + "/matches?status=FINISHED&limit=5");
            return json.getJSONArray("matches");
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch last matches for teamId="
                    + teamId + ": " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * Fetches H2H matches between two teams.
     * Pulls last 20 finished matches for homeId, then filters for
     * matches involving both teams.
     */
    public static JSONArray getHeadToHead(int homeId, int awayId) {
        try {
            JSONObject json = get("teams/" + homeId
                    + "/matches?status=FINISHED&limit=20");
            JSONArray all = json.getJSONArray("matches");

            JSONArray h2h = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject match = all.getJSONObject(i);
                int hId = match.getJSONObject("homeTeam").getInt("id");
                int aId = match.getJSONObject("awayTeam").getInt("id");
                if ((hId == homeId && aId == awayId)
                        || (hId == awayId && aId == homeId)) {
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
     * Also extracts referee name for RefereeAnalyzer.
     */
    public static JSONArray getRecentResults() {
        try {
            JSONObject json = get("competitions/" + PL_CODE
                    + "/matches?status=FINISHED&limit=10");
            return json.getJSONArray("matches");
        } catch (Exception e) {
            System.out.println("[WARN] Could not fetch recent results: " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * Calculates days of rest for a team since their last match.
     * Uses all competitions (PL, Europa, FA Cup etc).
     *
     * Returns the number of days between their most recent finished
     * match and today. Returns 7 if no recent match found (safe default
     * meaning no fatigue penalty applied).
     *
     * @param teamId  football-data.org team ID
     * @param today   today's date (passed in so Simulator doesn't need to know timezone)
     */
    public static int getDaysRest(int teamId, LocalDate today) {
        try {
            JSONObject json = get("teams/" + teamId
                    + "/matches?status=FINISHED&limit=1");
            JSONArray matches = json.getJSONArray("matches");

            if (matches.isEmpty()) {
                System.out.println("[FATIGUE] No recent match found for teamId="
                        + teamId + " — assuming fully rested");
                return 7;
            }

            JSONObject lastMatch = matches.getJSONObject(0);
            String dateStr = lastMatch.getString("utcDate").substring(0, 10);
            LocalDate lastMatchDate = LocalDate.parse(dateStr);
            int days = (int) ChronoUnit.DAYS.between(lastMatchDate, today);

            String competition = lastMatch
                    .getJSONObject("competition")
                    .getString("name");
            System.out.printf("[FATIGUE] teamId=%d last match=%s (%s) → %d days rest%n",
                    teamId, lastMatchDate, competition, days);

            return Math.max(days, 0);

        } catch (Exception e) {
            System.out.println("[FATIGUE] Could not fetch days rest for teamId="
                    + teamId + ": " + e.getMessage());
            return 7; // safe default — no penalty
        }
    }

    /**
     * Fetches the upcoming fixture between two teams to get the assigned referee.
     * Searches scheduled PL matches for the specific home/away combination.
     * Returns null if referee not yet assigned.
     */
    public static String getUpcomingReferee(String homeTeamName, String awayTeamName) {
        try {
            JSONObject json = get("competitions/" + PL_CODE
                    + "/matches?status=SCHEDULED&limit=20");
            JSONArray matches = json.getJSONArray("matches");

            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.getJSONObject(i);
                String home = match.getJSONObject("homeTeam").getString("name");
                String away = match.getJSONObject("awayTeam").getString("name");

                if (home.equalsIgnoreCase(homeTeamName)
                        && away.equalsIgnoreCase(awayTeamName)) {
                    String ref = RefereeAnalyzer.extractReferee(match);
                    if (ref != null) {
                        System.out.println("[REF] Referee for "
                                + homeTeamName + " vs " + awayTeamName
                                + ": " + ref);
                    } else {
                        System.out.println("[REF] No referee assigned yet for "
                                + homeTeamName + " vs " + awayTeamName);
                    }
                    return ref;
                }
            }
        } catch (Exception e) {
            System.out.println("[REF] Could not fetch referee: " + e.getMessage());
        }
        return null;
    }
}