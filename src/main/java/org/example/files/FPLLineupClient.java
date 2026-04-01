package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses the FPL fixtures endpoint to:
 *   1. Check if there are any PL games today
 *   2. Get today's games with SA kickoff times (for Telegram)
 *   3. Check if any game kicks off in the next 25-90 minutes (pre-match window)
 *   4. Load confirmed lineups from the FPL live endpoint
 *
 * All endpoints are free with no rate limits.
 */
public class FPLLineupClient {

    private static final String BASE_URL = "https://fantasy.premierleague.com/api/";
    private static final ZoneId SA_ZONE  = ZoneId.of("Africa/Johannesburg");
    private static final DateTimeFormatter SA_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final java.util.Map<Integer, Boolean> confirmedStarters
            = new java.util.HashMap<>();
    private static boolean lineupsLoaded = false;

    // Cached fixtures for this run
    private static JSONArray cachedFixtures = null;

    // -----------------------------------------------------------
    // Check if there are ANY games today
    // -----------------------------------------------------------
    public static boolean hasGamesToday() {
        JSONArray fixtures = getAllFixtures();
        LocalDate today    = LocalDate.now(SA_ZONE);

        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            String kickoffStr  = fixture.optString("kickoff_time", null);
            if (kickoffStr == null) continue;

            ZonedDateTime kickoff = ZonedDateTime.parse(kickoffStr,
                    DateTimeFormatter.ISO_DATE_TIME);
            LocalDate kickoffDate = kickoff.withZoneSameInstant(SA_ZONE).toLocalDate();

            if (kickoffDate.equals(today)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------
    // Get all today's games with SA kickoff times
    // -----------------------------------------------------------
    public static List<TelegramNotifier.UpcomingGame> getTodaysGames(
            java.util.Map<Integer, String> fplIdToFdName) {

        List<TelegramNotifier.UpcomingGame> games = new ArrayList<>();
        JSONArray fixtures = getAllFixtures();
        LocalDate today    = LocalDate.now(SA_ZONE);

        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            String kickoffStr  = fixture.optString("kickoff_time", null);
            if (kickoffStr == null) continue;

            boolean started  = fixture.optBoolean("started", false);
            boolean finished = fixture.optBoolean("finished", false);
            if (finished) continue;

            ZonedDateTime kickoffUTC = ZonedDateTime.parse(kickoffStr,
                    DateTimeFormatter.ISO_DATE_TIME);
            ZonedDateTime kickoffSA  = kickoffUTC.withZoneSameInstant(SA_ZONE);
            LocalDate kickoffDate    = kickoffSA.toLocalDate();

            if (!kickoffDate.equals(today)) continue;

            int homeId = fixture.optInt("team_h", -1);
            int awayId = fixture.optInt("team_a", -1);

            // Try to get readable names — fall back to FPL ID if not mapped
            String homeName = fplIdToFdName.getOrDefault(homeId, "Team " + homeId);
            String awayName = fplIdToFdName.getOrDefault(awayId, "Team " + awayId);
            String timeStr  = kickoffSA.format(SA_TIME_FMT);

            games.add(new TelegramNotifier.UpcomingGame(homeName, awayName, timeStr));
        }

        return games;
    }

    // -----------------------------------------------------------
    // Check if a game kicks off in the pre-match window (25-90 min)
    // -----------------------------------------------------------
    public static List<Integer> getTeamsPlayingSoon() {
        List<Integer> teamIds = new ArrayList<>();
        JSONArray     fixtures = getAllFixtures();
        ZonedDateTime now      = ZonedDateTime.now(ZoneOffset.UTC);

        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            boolean started    = fixture.optBoolean("started",  false);
            boolean finished   = fixture.optBoolean("finished", false);
            if (started || finished) continue;

            String kickoffStr = fixture.optString("kickoff_time", null);
            if (kickoffStr == null) continue;

            ZonedDateTime kickoff = ZonedDateTime.parse(kickoffStr,
                    DateTimeFormatter.ISO_DATE_TIME);
            long minutesUntil = java.time.Duration.between(now, kickoff).toMinutes();

            if (minutesUntil >= 25 && minutesUntil <= 90) {
                int homeId = fixture.optInt("team_h", -1);
                int awayId = fixture.optInt("team_a", -1);
                if (homeId > 0) teamIds.add(homeId);
                if (awayId > 0) teamIds.add(awayId);

                System.out.printf("[LINEUP] Match in %d minutes — FPL teams %d vs %d%n",
                        minutesUntil, homeId, awayId);
            }
        }
        return teamIds;
    }

    // -----------------------------------------------------------
    // Load confirmed lineups from FPL live endpoint
    // -----------------------------------------------------------
    public static void loadLineups(int gameweek) {
        if (lineupsLoaded) return;

        try {
            String    urlStr = BASE_URL + "event/" + gameweek + "/live/";
            JSONObject data  = fetch(urlStr);
            if (data == null) return;

            JSONArray elements = data.getJSONArray("elements");
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                int playerId       = element.getInt("id");

                // Check explain array for is_starter flag
                JSONArray explainArray = element.optJSONArray("explain");
                boolean isStarter = false;
                if (explainArray != null && explainArray.length() > 0) {
                    isStarter = explainArray.getJSONObject(0)
                            .optBoolean("is_starter", false);
                }
                confirmedStarters.put(playerId, isStarter);
            }

            System.out.println("[LINEUP] Loaded lineup data for gameweek " + gameweek);
            lineupsLoaded = true;

        } catch (Exception e) {
            System.out.println("[LINEUP] Could not load lineups: " + e.getMessage()
                    + " — using availability estimates");
        }
    }

    public static boolean isConfirmedStarter(int fplPlayerId) {
        if (!lineupsLoaded) return true;
        return confirmedStarters.getOrDefault(fplPlayerId, false);
    }

    public static boolean isLineupsLoaded() { return lineupsLoaded; }

    // -----------------------------------------------------------
    // Fetch all fixtures (cached for this run)
    // -----------------------------------------------------------
    public static JSONArray getAllFixtures() {
        if (cachedFixtures != null) return cachedFixtures;

        try {
            URL url = new URL(BASE_URL + "fixtures/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) return new JSONArray();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            cachedFixtures = new JSONArray(sb.toString());
            System.out.println("[LINEUP] Fixtures loaded — "
                    + cachedFixtures.length() + " total");
            return cachedFixtures;

        } catch (Exception e) {
            System.out.println("[LINEUP] Error fetching fixtures: " + e.getMessage());
            return new JSONArray();
        }
    }

    private static JSONObject fetch(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }
}