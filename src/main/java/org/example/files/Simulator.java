package org.example.files;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Random;

/**
 * Simulates a match using Monte Carlo Poisson sampling.
 *
 * New in this version:
 *
 * FEATURE 1 — FATIGUE PENALTY (sliding scale):
 *   Days rest between last match and today affects lambda.
 *   1 day rest  → 15% lambda reduction
 *   2 days rest → 10% lambda reduction
 *   3 days rest →  5% lambda reduction
 *   4+ days     → no penalty (fully rested)
 *   Applies to ALL competitions — Europa, FA Cup etc count.
 *
 * FEATURE 2 — REFEREE NUDGES:
 *   Over/under: if referee has 5+ games, their avg goals/game
 *               nudges both lambdas up or down (max ±15%)
 *   Match winner: if referee has 5+ games involving a specific
 *                 team AND shows >65% win rate for that team,
 *                 a small bias nudge is applied (max ±8%)
 *   Falls back gracefully to no adjustment if data is thin.
 */
public class Simulator {

    private static final int    SIMULATIONS    = 10_000;
    private static final double HOME_ADVANTAGE = 0.15;
    private static final Random RAND           = new Random();
    private static final ZoneId SA_ZONE        = ZoneId.of("Africa/Johannesburg");

    // Fatigue penalty per days rest
    private static final double FATIGUE_1_DAY  = 0.15;
    private static final double FATIGUE_2_DAYS = 0.10;
    private static final double FATIGUE_3_DAYS = 0.05;

    public static class SimulationResult {
        public final Prediction prediction;
        public final double lambdaHome;
        public final double lambdaAway;
        public final double homeWinProb;
        public final double drawProb;
        public final double awayWinProb;
        public final double over15Prob;
        public final double over25Prob;
        public final double over35Prob;

        public SimulationResult(Prediction prediction,
                                double lambdaHome, double lambdaAway,
                                double homeWinProb, double drawProb, double awayWinProb,
                                double over15Prob, double over25Prob, double over35Prob) {
            this.prediction  = prediction;
            this.lambdaHome  = lambdaHome;
            this.lambdaAway  = lambdaAway;
            this.homeWinProb = homeWinProb;
            this.drawProb    = drawProb;
            this.awayWinProb = awayWinProb;
            this.over15Prob  = over15Prob;
            this.over25Prob  = over25Prob;
            this.over35Prob  = over35Prob;
        }
    }

    public static SimulationResult simulate(Team home, Team away) {

        LocalDate today = LocalDate.now(SA_ZONE);

        double eloAdjustment = EloRatingSystem.expectedScore(
                home.getElo(), away.getElo());

        double homeAttack  = home.getHomeAttack();
        double homeDefense = home.getHomeDefense();
        double awayAttack  = away.getAwayAttack();
        double awayDefense = away.getAwayDefense();

        System.out.printf("[SIM] %s  homeAtt=%.2f homeDef=%.2f%n",
                home.getName(), homeAttack, homeDefense);
        System.out.printf("[SIM] %s  awayAtt=%.2f awayDef=%.2f%n",
                away.getName(), awayAttack, awayDefense);

        // --- Base lambdas ---
        // Feature 6: Per-team home advantage (falls back to global if not enough data)
        double homeAdv = home.getHomeAdvantage() >= 0
                ? home.getHomeAdvantage()
                : HOME_ADVANTAGE;

        double lambdaHome = homeAttack
                * (1.0 / Math.max(awayDefense, 0.5))
                * (0.8 + eloAdjustment * 0.4)
                * (1.0 + homeAdv);

        double lambdaAway = awayAttack
                * (1.0 / Math.max(homeDefense, 0.5))
                * (1.2 - eloAdjustment * 0.4);

        System.out.printf("[HOME-ADV] %s using homeAdv=%.3f (global=%.2f)%n",
                home.getName(), homeAdv, HOME_ADVANTAGE);

        // --- H2H advantage ---
        double h2hAdvantage = HeadToHeadAnalyzer.getAdvantage(
                home.getId(), away.getId());
        lambdaHome = Math.max(lambdaHome * (1.0 + h2hAdvantage), 0.1);
        lambdaAway = Math.max(lambdaAway * (1.0 - h2hAdvantage), 0.1);

        // Feature 4: H2H scoreline weights for Monte Carlo blending
        Map<String, Double> scorelineWeights = HeadToHeadAnalyzer.getScorelineWeights(
                home.getId(), away.getId());
        boolean hasScorelineData = !scorelineWeights.isEmpty();

        System.out.printf("[SIM] After H2H: %s=%.2f  %s=%.2f  (ELO=%.2f H2H=%.2f)%n",
                home.getName(), lambdaHome, away.getName(), lambdaAway,
                eloAdjustment, h2hAdvantage);

        // --- FEATURE 1: Fatigue penalty ---
        int homeDaysRest = APIFootballClient.getDaysRest(home.getId(), today);
        int awayDaysRest = APIFootballClient.getDaysRest(away.getId(), today);

        double homeFatigueMult = getFatigueMultiplier(homeDaysRest, home.getName());
        double awayFatigueMult = getFatigueMultiplier(awayDaysRest, away.getName());

        lambdaHome *= homeFatigueMult;
        lambdaAway *= awayFatigueMult;

        System.out.printf("[FATIGUE] %s daysRest=%d mult=%.2f → lambda=%.2f%n",
                home.getName(), homeDaysRest, homeFatigueMult, lambdaHome);
        System.out.printf("[FATIGUE] %s daysRest=%d mult=%.2f → lambda=%.2f%n",
                away.getName(), awayDaysRest, awayFatigueMult, lambdaAway);

        // --- FEATURE 2: Referee nudges ---
        String referee = APIFootballClient.getUpcomingReferee(
                home.getName(), away.getName());

        if (referee != null) {
            // Over/under nudge — applies to both lambdas equally
            double refLambdaMult = RefereeAnalyzer.getLambdaMultiplier(referee);
            lambdaHome *= refLambdaMult;
            lambdaAway *= refLambdaMult;

            // Match winner nudge — home team bias based on ref history
            double refBiasMult = RefereeAnalyzer.getHomeTeamBiasMultiplier(
                    referee, home.getName(), away.getName());
            lambdaHome *= refBiasMult;
        }

        // Ensure lambdas stay positive
        lambdaHome = Math.max(lambdaHome, 0.1);
        lambdaAway = Math.max(lambdaAway, 0.1);

        System.out.printf("[SIM] Final lambda: %s=%.2f  %s=%.2f  " +
                        "(ref=%s)%n",
                home.getName(), lambdaHome,
                away.getName(), lambdaAway,
                referee != null ? referee : "unassigned");
        System.out.printf("[SIM] Expected total goals: %.2f%n",
                lambdaHome + lambdaAway);

        // --- Monte Carlo simulation ---
        // Feature 4: blend H2H scoreline weights into simulation
        // SCORELINE_BLEND% of simulations use historical scoreline distribution,
        // remaining (1-SCORELINE_BLEND)% use pure Poisson
        double scorelineBlend = hasScorelineData
                ? HeadToHeadAnalyzer.getScorelineBlend() : 0.0;
        int h2hSims  = (int) (SIMULATIONS * scorelineBlend);
        int poisSims = SIMULATIONS - h2hSims;

        int homeWins = 0, awayWins = 0, draws = 0;
        int over15   = 0, over25   = 0, over35 = 0;

        // Pure Poisson simulations
        for (int i = 0; i < poisSims; i++) {
            int hGoals = poissonSample(lambdaHome);
            int aGoals = poissonSample(lambdaAway);
            int total  = hGoals + aGoals;
            if (hGoals > aGoals)      homeWins++;
            else if (aGoals > hGoals) awayWins++;
            else                      draws++;
            if (total > 1) over15++;
            if (total > 2) over25++;
            if (total > 3) over35++;
        }

        // H2H scoreline-weighted simulations
        if (h2hSims > 0) {
            // Build cumulative distribution from scoreline weights
            String[] scorelineKeys = scorelineWeights.keySet()
                    .toArray(new String[0]);
            double[] cumulative = new double[scorelineKeys.length];
            double cum = 0;
            for (int j = 0; j < scorelineKeys.length; j++) {
                cum += scorelineWeights.get(scorelineKeys[j]);
                cumulative[j] = cum;
            }

            for (int i = 0; i < h2hSims; i++) {
                double r = RAND.nextDouble();
                String chosen = scorelineKeys[scorelineKeys.length - 1];
                for (int j = 0; j < cumulative.length; j++) {
                    if (r <= cumulative[j]) { chosen = scorelineKeys[j]; break; }
                }
                String[] parts = chosen.split("-");
                int hGoals = Integer.parseInt(parts[0]);
                int aGoals = Integer.parseInt(parts[1]);
                int total  = hGoals + aGoals;
                if (hGoals > aGoals)      homeWins++;
                else if (aGoals > hGoals) awayWins++;
                else                      draws++;
                if (total > 1) over15++;
                if (total > 2) over25++;
                if (total > 3) over35++;
            }
            System.out.printf("[H2H-SCORE] Blended %d H2H scoreline sims (%.0f%%)%n",
                    h2hSims, scorelineBlend * 100);
        }

        double homeWinProb = 100.0 * homeWins / SIMULATIONS;
        double awayWinProb = 100.0 * awayWins / SIMULATIONS;
        double drawProb    = 100.0 * draws    / SIMULATIONS;
        double over15Prob  = 100.0 * over15   / SIMULATIONS;
        double over25Prob  = 100.0 * over25   / SIMULATIONS;
        double over35Prob  = 100.0 * over35   / SIMULATIONS;

        System.out.printf("[SIM] Raw model: H=%.1f%% D=%.1f%% A=%.1f%%  " +
                        "Over: 1.5=%.1f%% 2.5=%.1f%% 3.5=%.1f%%%n",
                homeWinProb, drawProb, awayWinProb,
                over15Prob, over25Prob, over35Prob);

        int predictedHome = (int) Math.round(lambdaHome);
        int predictedAway = (int) Math.round(lambdaAway);

        Prediction prediction = new Prediction(
                home.getName(), away.getName(),
                predictedHome, predictedAway,
                homeWinProb, awayWinProb, drawProb);

        return new SimulationResult(prediction,
                lambdaHome, lambdaAway,
                homeWinProb, drawProb, awayWinProb,
                over15Prob, over25Prob, over35Prob);
    }

    /**
     * Returns a lambda multiplier based on days rest.
     * 1 day  → 0.85 (15% reduction)
     * 2 days → 0.90 (10% reduction)
     * 3 days → 0.95 ( 5% reduction)
     * 4+     → 1.00 (no penalty)
     */
    private static double getFatigueMultiplier(int daysRest, String teamName) {
        if (daysRest <= 1) {
            System.out.printf("[FATIGUE] %s — 1 day rest: applying %.0f%% penalty%n",
                    teamName, FATIGUE_1_DAY * 100);
            return 1.0 - FATIGUE_1_DAY;
        } else if (daysRest == 2) {
            System.out.printf("[FATIGUE] %s — 2 days rest: applying %.0f%% penalty%n",
                    teamName, FATIGUE_2_DAYS * 100);
            return 1.0 - FATIGUE_2_DAYS;
        } else if (daysRest == 3) {
            System.out.printf("[FATIGUE] %s — 3 days rest: applying %.0f%% penalty%n",
                    teamName, FATIGUE_3_DAYS * 100);
            return 1.0 - FATIGUE_3_DAYS;
        }
        return 1.0; // fully rested
    }

    private static int poissonSample(double lambda) {
        double L = Math.exp(-lambda);
        int    k = 0;
        double p = 1.0;
        do {
            k++;
            p *= RAND.nextDouble();
        } while (p > L);
        return k - 1;
    }
}