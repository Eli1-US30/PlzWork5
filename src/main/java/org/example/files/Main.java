package org.example.files;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("         SOCCER PREDICTOR v2.7");
        System.out.println("==============================================\n");

        // --------------------------------------------------------
        // STEP 1: Check FPL fixtures — one free API call
        // Decides what kind of run this is before touching
        // any rate-limited APIs
        // --------------------------------------------------------
        System.out.println("[INIT] Checking FPL fixtures...");

        boolean gamesToday      = FPLLineupClient.hasGamesToday();
        List<Integer> teamsSoon = FPLLineupClient.getTeamsPlayingSoon();
        boolean isPreMatchRun   = !teamsSoon.isEmpty();

        // No games today at all — send short notification and exit
        if (!gamesToday) {
            System.out.println("[INIT] No games today — sending notification and exiting");
            TelegramNotifier.sendNoGamesToday();
            System.out.println("==============================================");
            return;
        }

        // Games today but not in our 25-90 min window yet
        // Send "games today" notification once per day (first run that finds games)
        if (!isPreMatchRun) {
            System.out.println("[INIT] Games today but not in prediction window yet");

            // Build a simple FPL ID -> name map for the games today message
            // We use a lightweight version here before full cache loads
            java.util.Map<Integer, String> fplIdToName = buildLightFplNameMap();
            List<TelegramNotifier.UpcomingGame> todaysGames =
                    FPLLineupClient.getTodaysGames(fplIdToName);

            if (!todaysGames.isEmpty()) {
                TelegramNotifier.sendGamesToday(todaysGames);
            }

            System.out.println("[INIT] Exiting — will predict when window opens");
            System.out.println("==============================================");
            return;
        }

        // Pre-match window — full prediction run
        System.out.println("[INIT] Pre-match window — running full prediction");

        // --------------------------------------------------------
        // STEP 2: Load persisted ELO and reconcile results
        // Result reconciliation also sends Telegram result messages
        // --------------------------------------------------------
        EloStore.load();
        PredictionLogger.init();
        ResultTracker.reconcile();

        // --------------------------------------------------------
        // STEP 3: Load confirmed lineups before FPLDataCache
        // so the cache uses confirmed starters
        // --------------------------------------------------------
        JSONBootstrapHelper.loadCurrentGameweek();

        // --------------------------------------------------------
        // STEP 4: Load all data sources
        // --------------------------------------------------------
        FPLDataCache.initialize();
        TheSportsDBClient.preload();
        StandingsClient.preload();
        OddsClient.preload();

        // --------------------------------------------------------
        // STEP 5: Get today's fixtures from football-data.org
        // --------------------------------------------------------
        List<Match> matches = FixtureFetcher.getMatchesForTeams(teamsSoon);

        if (matches.isEmpty()) {
            System.out.println("[INIT] No matches found for prediction window");
            System.out.println("==============================================");
            return;
        }

        System.out.println("\nPredicting " + matches.size() + " match(es)\n");

        // --------------------------------------------------------
        // STEP 6: Predict each match
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
                        sim.over15Prob,  sim.over25Prob, sim.over35Prob);

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
                System.out.printf("  Over 1.5 : %.1f%%  Over 2.5 : %.1f%%  Over 3.5 : %.1f%%%n",
                        blended.over15Prob, blended.over25Prob, blended.over35Prob);
                System.out.println("  ✅ Confirmed lineups used");
                System.out.println();

            } catch (Exception e) {
                System.out.println("[ERROR] Failed to predict "
                        + match + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // --------------------------------------------------------
        // STEP 7: Send predictions to Telegram
        // --------------------------------------------------------
        if (!finalPredictions.isEmpty()) {
            System.out.println("[TELEGRAM] Sending predictions...");
            TelegramNotifier.sendPredictions(matches, finalPredictions, blendedList);
        }

        // --------------------------------------------------------
        // STEP 8: Print ELO standings to console
        // --------------------------------------------------------
        EloStore.printStandings();

        System.out.println("==============================================");
        System.out.println("           Predictions complete");
        System.out.println("==============================================");
    }

    /**
     * Builds a lightweight FPL team ID -> display name map
     * using only the bootstrap teams array.
     * Used for the "games today" message before full cache loads.
     */
    private static java.util.Map<Integer, String> buildLightFplNameMap() {
        java.util.Map<Integer, String> map = new java.util.HashMap<>();
        try {
            org.json.JSONObject bootstrap = FPLClient.getBootstrapStatic();
            if (bootstrap == null) return map;
            org.json.JSONArray teams = bootstrap.getJSONArray("teams");
            for (int i = 0; i < teams.length(); i++) {
                org.json.JSONObject team = teams.getJSONObject(i);
                map.put(team.getInt("id"), team.getString("name"));
            }
        } catch (Exception e) {
            System.out.println("[INIT] Could not build FPL name map: " + e.getMessage());
        }
        return map;
    }
}