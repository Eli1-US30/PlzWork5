package org.example.files;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("         SOCCER PREDICTOR v2.5");
        System.out.println("==============================================\n");

        // --------------------------------------------------------
        // STEP 1: Early fixture check — one cheap API call.
        // If no games this week exit immediately — saves all
        // FPL, odds, standings and ELO API requests.
        // --------------------------------------------------------
        System.out.println("[INIT] Checking for upcoming fixtures...");
        List<Match> matches = FixtureFetcher.getUpcomingMatches();

        if (matches.isEmpty()) {
            System.out.println("[INIT] No fixtures in the next 7 days — nothing to predict.");
            System.out.println("[INIT] Exiting without consuming additional API requests.");
            System.out.println("==============================================");
            return;
        }

        System.out.println("[INIT] Found " + matches.size()
                + " fixtures — loading data sources...\n");

        // --------------------------------------------------------
        // STEP 2: Load persisted ELO and reconcile last results
        // --------------------------------------------------------
        EloStore.load();
        PredictionLogger.init();
        ResultTracker.reconcile();

        // --------------------------------------------------------
        // STEP 3: Load all data sources
        // --------------------------------------------------------
        FPLDataCache.initialize();
        TheSportsDBClient.preload();
        StandingsClient.preload();
        OddsClient.preload();

        // --------------------------------------------------------
        // STEP 4: Predict each match, collect for Telegram
        // --------------------------------------------------------
        List<Prediction> finalPredictions = new ArrayList<>();
        List<OddsBlender.BlendedProbabilities> blendedList = new ArrayList<>();

        System.out.println("\nFound " + matches.size() + " upcoming fixtures\n");

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

                // Raw simulation
                Simulator.SimulationResult sim = Simulator.simulate(home, away);

                // Blend with bookmaker fair probabilities
                OddsBlender.BlendedProbabilities blended = OddsBlender.blend(
                        match.getHomeTeamName(), match.getAwayTeamName(),
                        sim.homeWinProb, sim.drawProb, sim.awayWinProb,
                        sim.over15Prob,  sim.over25Prob, sim.over35Prob
                );

                // Final prediction using blended probabilities
                Prediction finalPrediction = new Prediction(
                        home.getName(), away.getName(),
                        sim.prediction.getHomeGoals(),
                        sim.prediction.getAwayGoals(),
                        blended.homeWinProb,
                        blended.awayWinProb,
                        blended.drawProb
                );

                // Log to CSV
                PredictionLogger.log(finalPrediction, home, away);

                // Collect for Telegram
                finalPredictions.add(finalPrediction);
                blendedList.add(blended);

                // Console output
                System.out.println();
                System.out.println(finalPrediction);
                System.out.printf("  Over 1.5 Goals : %.1f%%%n", blended.over15Prob);
                System.out.printf("  Over 2.5 Goals : %.1f%%%n", blended.over25Prob);
                System.out.printf("  Over 3.5 Goals : %.1f%%%n", blended.over35Prob);
                if (blended.oddsUsed) {
                    System.out.printf("  (blended with %s odds)%n", blended.bookmaker);
                } else {
                    System.out.println("  (model only — no odds available)");
                }
                System.out.println();

            } catch (Exception e) {
                System.out.println("[ERROR] Failed to predict "
                        + match + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // --------------------------------------------------------
        // STEP 5: Send Telegram notification with all predictions
        // --------------------------------------------------------
        if (!finalPredictions.isEmpty()) {
            System.out.println("[TELEGRAM] Sending predictions to phone...");
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