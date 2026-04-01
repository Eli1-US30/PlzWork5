package org.example.files;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final ZoneId  SA_ZONE            = ZoneId.of("Africa/Johannesburg");
    private static final String  NO_GAMES_FILE       = "data/last_no_games_date.txt";
    private static final String  GAMES_TODAY_FILE    = "data/last_games_today_date.txt";

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("         SOCCER PREDICTOR v2.8");
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

        String todaySA = LocalDate.now(SA_ZONE).toString(); // e.g. "2026-04-05"

        // --------------------------------------------------------
        // No games today — send notification ONCE per day then exit
        // --------------------------------------------------------
        if (!gamesToday) {
            System.out.println("[INIT] No games today");

            if (!alreadySentToday(NO_GAMES_FILE, todaySA)) {
                System.out.println("[INIT] First check today — sending no-games notification");
                TelegramNotifier.sendNoGamesToday();
                saveSentDate(NO_GAMES_FILE, todaySA);
            } else {
                System.out.println("[INIT] Already sent no-games notification today — skipping");
            }

            System.out.println("==============================================");
            return;
        }

        // --------------------------------------------------------
        // Games today but not in our 25-90 min window yet
        // Send "games today" notification ONCE per day then exit
        // --------------------------------------------------------
        if (!isPreMatchRun) {
            System.out.println("[INIT] Games today but not in prediction window yet");

            if (!alreadySentToday(GAMES_TODAY_FILE, todaySA)) {
                System.out.println("[INIT] First check today — sending games today notification");
                java.util.Map<Integer, String> fplIdToName = buildLightFplNameMap();
                List<TelegramNotifier.UpcomingGame> todaysGames =
                        FPLLineupClient.getTodaysGames(fplIdToName);

                if (!todaysGames.isEmpty()) {
                    TelegramNotifier.sendGamesToday(todaysGames);
                    saveSentDate(GAMES_TODAY_FILE, todaySA);
                }
            } else {
                System.out.println("[INIT] Already sent games today notification — skipping");
            }

            System.out.println("[INIT] Exiting — will predict when window opens");
            System.out.println("==============================================");
            return;
        }

        // --------------------------------------------------------
        // Pre-match window — full prediction run
        // --------------------------------------------------------
        System.out.println("[INIT] Pre-match window — running full prediction");

        // --------------------------------------------------------
        // STEP 2: Load persisted ELO and reconcile results
        // --------------------------------------------------------
        EloStore.load();
        PredictionLogger.init();
        ResultTracker.reconcile();

        // --------------------------------------------------------
        // STEP 3: Load confirmed lineups before FPLDataCache
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

    // --------------------------------------------------------
    // Checks if we already sent a notification today.
    // Reads the date stored in the given file and compares
    // to today's SA date.
    // --------------------------------------------------------
    private static boolean alreadySentToday(String filePath, String todaySA) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return false;
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String lastDate = r.readLine();
                return todaySA.equals(lastDate != null ? lastDate.trim() : "");
            }
        } catch (Exception e) {
            System.out.println("[INIT] Could not read date file " + filePath
                    + ": " + e.getMessage());
            return false;
        }
    }

    // --------------------------------------------------------
    // Saves today's SA date to a file so we know we already sent.
    // --------------------------------------------------------
    private static void saveSentDate(String filePath, String todaySA) {
        try {
            new File("data").mkdirs();
            try (FileWriter fw = new FileWriter(filePath)) {
                fw.write(todaySA);
            }
        } catch (Exception e) {
            System.out.println("[INIT] Could not save date file " + filePath
                    + ": " + e.getMessage());
        }
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