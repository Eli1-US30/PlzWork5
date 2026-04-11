package org.example.files;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Soccer Predictor v3.2 — Morning Run
 *
 * Runs once every morning at 6am UTC (8am SA time).
 * Predicts ALL upcoming PL matches in the next 7 days.
 * Sends one Telegram message with all predictions for the week.
 *
 * Why morning runs:
 *   - Zero timing issues — runs at a fixed reliable time
 *   - GitHub Actions at 6am UTC is consistent with minimal queue delays
 *   - Covers the full week so weekend games are never missed
 *   - Lineups not confirmed yet but ELO, xG, form, H2H and
 *     referee data do the heavy lifting at this stage of the season
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
        // STEP 2: Get upcoming fixtures for the next 7 days
        // --------------------------------------------------------
        System.out.println("[INIT] Fetching upcoming fixtures...");
        List<Match> matches = FixtureFetcher.getUpcomingMatches();

        if (matches.isEmpty()) {
            System.out.println("[INIT] No fixtures in the next 7 days");
            TelegramNotifier.sendNoGamesToday();
            System.out.println("==============================================");
            return;
        }

        System.out.println("[INIT] Found " + matches.size()
                + " fixture(s) — loading data sources...\n");

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
        // STEP 5: Send all predictions to Telegram
        // --------------------------------------------------------
        if (!finalPredictions.isEmpty()) {
            System.out.println("[TELEGRAM] Sending weekly predictions...");
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
}