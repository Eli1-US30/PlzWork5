package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Soccer Predictor v3.2 — Morning Run
 *
 * Runs once every morning at 6am UTC (8am SA time).
 * Predicts matches happening TOMORROW only.
 *
 * Logic:
 *   - No matches tomorrow → exit silently (no spam)
 *   - Matches tomorrow → full prediction → one Telegram message
 *
 * This means:
 *   - Friday morning → predicts Saturday games
 *   - Saturday morning → predicts Sunday games
 *   - Monday morning → predicts Tuesday games
 *   - Data is always fresh — predictions made the day before
 *
 * Future upgrade (after 2 seasons of data):
 *   - Switch to Oracle Cloud VM for confirmed-lineup predictions
 *   - Add ML model trained on accumulated prediction data
 */
public class Main {

    private static final ZoneId SA_ZONE = ZoneId.of("Africa/Johannesburg");

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("      SOCCER PREDICTOR v3.2 — Morning Run");
        System.out.println("==============================================\n");

        // --------------------------------------------------------
        // STEP 1: Load persisted data and reconcile last results
        // --------------------------------------------------------
        EloStore.load();
        RefereeAnalyzer.load();
        PredictionLogger.init();
        ResultTracker.reconcile();

        // --------------------------------------------------------
        // STEP 2: Check for matches tomorrow
        // --------------------------------------------------------
        LocalDate tomorrow = LocalDate.now(SA_ZONE).plusDays(1);
        System.out.println("[INIT] Checking for matches on "
                + tomorrow.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + "...");

        List<Match> matches = getTomorrowsMatches(tomorrow);

        if (matches.isEmpty()) {
            System.out.println("[INIT] No matches tomorrow — exiting silently");
            // Uncomment below to get a Telegram message when no games found:
            // TelegramNotifier.sendNoGamesToday();
            System.out.println("==============================================");
            return;
        }

        System.out.println("[INIT] Found " + matches.size()
                + " match(es) tomorrow — loading data sources...\n");

        // --------------------------------------------------------
        // STEP 3: Load all data sources
        // --------------------------------------------------------
        JSONBootstrapHelper.loadCurrentGameweek();
        FPLDataCache.initialize();
        TheSportsDBClient.preload();
        StandingsClient.preload();
        OddsClient.preload();

        // --------------------------------------------------------
        // STEP 4: Predict each match
        // --------------------------------------------------------
        List<Prediction> finalPredictions = new ArrayList<>();
        List<OddsBlender.BlendedProbabilities> blendedList = new ArrayList<>();

        for (Match match : matches) {
            System.out.println("==============================================");
            System.out.println("  " + match.getHomeTeamName()
                    + " vs " + match.getAwayTeamName());
            System.out.println("==============================================");

            try {
                Team home = StatsCalculator.buildTeam(
                        match.getHomeTeamId(), match.getHomeTeamName());
                Team away = StatsCalculator.buildTeam(
                        match.getAwayTeamId(), match.getAwayTeamName());

                Simulator.SimulationResult sim = Simulator.simulate(home, away);

                OddsBlender.BlendedProbabilities blended = OddsBlender.blend(
                        match.getHomeTeamName(), match.getAwayTeamName(),
                        sim.homeWinProb, sim.drawProb, sim.awayWinProb,
                        sim.over15Prob, sim.over25Prob, sim.over35Prob);

                Prediction finalPrediction = new Prediction(
                        home.getName(), away.getName(),
                        sim.prediction.getHomeGoals(),
                        sim.prediction.getAwayGoals(),
                        blended.homeWinProb,
                        blended.awayWinProb,
                        blended.drawProb);

                PredictionLogger.log(finalPrediction, home, away);
                finalPredictions.add(finalPrediction);
                blendedList.add(blended);

                System.out.println();
                System.out.println(finalPrediction);
                System.out.printf("  Over 1.5: %.1f%%  2.5: %.1f%%  3.5: %.1f%%%n",
                        blended.over15Prob, blended.over25Prob, blended.over35Prob);
                System.out.println();

            } catch (Exception e) {
                System.out.println("[ERROR] Failed to predict "
                        + match + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // --------------------------------------------------------
        // STEP 5: Send predictions to Telegram
        // --------------------------------------------------------
        if (!finalPredictions.isEmpty()) {
            System.out.println("[TELEGRAM] Sending predictions for tomorrow...");
            TelegramNotifier.sendPredictions(matches, finalPredictions, blendedList);
        }

        // --------------------------------------------------------
        // STEP 6: Print ELO standings to console
        // --------------------------------------------------------
        EloStore.printStandings();

        System.out.println("==============================================");
        System.out.println("           Predictions complete");
        System.out.println("==============================================");
    }

    // --------------------------------------------------------
    // Fetch only matches scheduled for tomorrow (SA date).
    // Converts UTC kickoff time to SA time before comparing
    // dates — fixes the bug where matches were skipped because
    // a Saturday 3pm UTC match has UTC date Saturday but
    // SA date is also Saturday, and we want tomorrow in SA time.
    // --------------------------------------------------------
    private static List<Match> getTomorrowsMatches(LocalDate tomorrow) {
        List<Match> tomorrowMatches = new ArrayList<>();

        // Wide date range so we never miss a match due to UTC/SA edge cases
        // API call is cheap — we filter properly below
        LocalDate from = tomorrow.minusDays(1);
        LocalDate to   = tomorrow.plusDays(1);

        JSONArray fixtures = APIFootballClient.getFixturesForDateRange(from, to);
        System.out.printf("[INIT] API returned %d fixture(s) in date range%n",
                fixtures.length());

        for (int i = 0; i < fixtures.length(); i++) {
            try {
                JSONObject fixture = fixtures.getJSONObject(i);

                // Get the full UTC kickoff datetime and convert to SA date
                String utcDateStr = fixture.getString("utcDate");
                ZonedDateTime kickoffUTC = ZonedDateTime.parse(utcDateStr,
                        java.time.format.DateTimeFormatter.ISO_DATE_TIME);
                LocalDate matchDateSA = kickoffUTC
                        .withZoneSameInstant(SA_ZONE)
                        .toLocalDate();

                System.out.printf("[FIXTURE-CHECK] UTC=%s → SA date=%s (tomorrow=%s)%n",
                        utcDateStr.substring(0, 16), matchDateSA, tomorrow);

                // Only include matches on tomorrow's SA date
                if (!matchDateSA.equals(tomorrow)) continue;

                int fixtureId = fixture.getInt("id");
                JSONObject home = fixture.getJSONObject("homeTeam");
                JSONObject away = fixture.getJSONObject("awayTeam");

                int    homeId   = home.getInt("id");
                String homeName = home.getString("name");
                int    awayId   = away.getInt("id");
                String awayName = away.getString("name");

                tomorrowMatches.add(
                        new Match(fixtureId, homeId, homeName, awayId, awayName));

                System.out.printf("[FIXTURE] ✅ %s vs %s on %s SA%n",
                        homeName, awayName, matchDateSA);

            } catch (Exception e) {
                System.out.println("[WARN] Could not parse fixture: "
                        + e.getMessage());
            }
        }

        return tomorrowMatches;
    }
}