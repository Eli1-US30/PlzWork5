package org.example.files;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for GitHub Actions deployment.
 *
 * Runs once per trigger and exits — GitHub Actions handles scheduling.
 * Cron: every 30 minutes, 7am-9pm UTC (9am-11pm SA time)
 *
 * Each run:
 *   1. Check FPL fixtures — one free API call
 *   2. No games today → send notification ONCE per day → exit
 *   3. Games today but not in window → send games list ONCE per day → exit
 *   4. Match in 25-120 min window → full prediction run
 *
 * Window widened from 90 to 120 minutes to handle GitHub Actions delays.
 */
public class Main {

    private static final ZoneId SA_ZONE          = ZoneId.of("Africa/Johannesburg");
    private static final String NO_GAMES_FILE    = "data/last_no_games_date.txt";
    private static final String GAMES_TODAY_FILE = "data/last_games_today_date.txt";

    // Widened window — 25-120 min handles GitHub delays up to 90 min
    private static final int    WINDOW_MIN       = 25;
    private static final int    WINDOW_MAX       = 120;

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("         SOCCER PREDICTOR v3.1");
        System.out.println("==============================================\n");

        System.out.println("[INIT] Checking FPL fixtures...");

        boolean gamesToday      = FPLLineupClient.hasGamesToday();
        List<Integer> teamsSoon = getTeamsInWindow();
        boolean isPreMatchRun   = !teamsSoon.isEmpty();

        String todaySA = LocalDate.now(SA_ZONE).toString();

        // --------------------------------------------------------
        // No games today — send once per day then exit
        // --------------------------------------------------------
        if (!gamesToday) {
            System.out.println("[INIT] No games today");
            if (!alreadySentToday(NO_GAMES_FILE, todaySA)) {
                TelegramNotifier.sendNoGamesToday();
                saveSentDate(NO_GAMES_FILE, todaySA);
                System.out.println("[INIT] Sent no-games notification");
            } else {
                System.out.println("[INIT] Already sent no-games today — skipping");
            }
            System.out.println("==============================================");
            return;
        }

        // --------------------------------------------------------
        // Games today but not in window — send once per day then exit
        // --------------------------------------------------------
        if (!isPreMatchRun) {
            System.out.println("[INIT] Games today but not in prediction window");
            if (!alreadySentToday(GAMES_TODAY_FILE, todaySA)) {
                java.util.Map<Integer, String> fplIdToName = buildLightFplNameMap();
                List<TelegramNotifier.UpcomingGame> todaysGames =
                        FPLLineupClient.getTodaysGames(fplIdToName);
                if (!todaysGames.isEmpty()) {
                    TelegramNotifier.sendGamesToday(todaysGames);
                    saveSentDate(GAMES_TODAY_FILE, todaySA);
                    System.out.println("[INIT] Sent games today notification");
                }
            } else {
                System.out.println("[INIT] Already sent games today — skipping");
            }
            System.out.println("[INIT] Exiting — will predict when window opens");
            System.out.println("==============================================");
            return;
        }

        // --------------------------------------------------------
        // Pre-match window — full prediction run
        // --------------------------------------------------------
        System.out.println("[INIT] Pre-match window — running full prediction");

        EloStore.load();
        PredictionLogger.init();
        ResultTracker.reconcile();
        RefereeAnalyzer.load();

        JSONBootstrapHelper.loadCurrentGameweek();

        FPLDataCache.initialize();
        TheSportsDBClient.preload();
        StandingsClient.preload();
        OddsClient.preload();

        List<Match> matches = FixtureFetcher.getMatchesForTeams(teamsSoon);

        if (matches.isEmpty()) {
            System.out.println("[INIT] No matches found for prediction window");
            System.out.println("==============================================");
            return;
        }

        System.out.println("\nPredicting " + matches.size() + " match(es)\n");

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

        if (!finalPredictions.isEmpty()) {
            System.out.println("[TELEGRAM] Sending predictions...");
            TelegramNotifier.sendPredictions(matches, finalPredictions, blendedList);
        }

        EloStore.printStandings();

        System.out.println("==============================================");
        System.out.println("           Predictions complete");
        System.out.println("==============================================");
    }

    // --------------------------------------------------------
    // Get teams with a match in WINDOW_MIN to WINDOW_MAX minutes
    // Uses FPL fixtures directly — no extra API call needed
    // --------------------------------------------------------
    private static List<Integer> getTeamsInWindow() {
        org.json.JSONArray fixtures = FPLLineupClient.getAllFixtures();
        List<Integer> teamIds = new ArrayList<>();
        java.time.ZonedDateTime now =
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);

        for (int i = 0; i < fixtures.length(); i++) {
            try {
                org.json.JSONObject fixture = fixtures.getJSONObject(i);
                if (fixture.optBoolean("started",  false)) continue;
                if (fixture.optBoolean("finished", false)) continue;

                String kickoffStr = fixture.optString("kickoff_time", null);
                if (kickoffStr == null) continue;

                java.time.ZonedDateTime kickoff = java.time.ZonedDateTime.parse(
                        kickoffStr,
                        java.time.format.DateTimeFormatter.ISO_DATE_TIME);
                long minutesUntil =
                        java.time.Duration.between(now, kickoff).toMinutes();

                if (minutesUntil >= WINDOW_MIN && minutesUntil <= WINDOW_MAX) {
                    int homeId = fixture.optInt("team_h", -1);
                    int awayId = fixture.optInt("team_a", -1);
                    if (homeId > 0) teamIds.add(homeId);
                    if (awayId > 0) teamIds.add(awayId);
                    System.out.printf("[WINDOW] Match in %d min — FPL teams %d vs %d%n",
                            minutesUntil, homeId, awayId);
                }
            } catch (Exception e) {
                System.out.println("[WINDOW] Parse error: " + e.getMessage());
            }
        }
        return teamIds;
    }

    // --------------------------------------------------------
    // Once-per-day throttle helpers
    // --------------------------------------------------------
    private static boolean alreadySentToday(String filePath, String todaySA) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return false;
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String last = r.readLine();
                return todaySA.equals(last != null ? last.trim() : "");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveSentDate(String filePath, String todaySA) {
        try {
            new File("data").mkdirs();
            try (FileWriter fw = new FileWriter(filePath)) {
                fw.write(todaySA);
            }
        } catch (Exception e) {
            System.out.println("[INIT] Could not save date file: " + e.getMessage());
        }
    }

    // --------------------------------------------------------
    // Lightweight FPL ID -> name map
    // --------------------------------------------------------
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
            System.out.println("[INIT] FPL name map error: " + e.getMessage());
        }
        return map;
    }
}