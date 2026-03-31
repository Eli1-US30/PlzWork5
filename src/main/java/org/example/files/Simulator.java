package org.example.files;

import java.util.Random;

/**
 * Simulates a match using Monte Carlo Poisson sampling.
 * Returns raw model probabilities — blending with bookmaker odds
 * happens downstream in OddsBlender before final output.
 */
public class Simulator {

    private static final int    SIMULATIONS    = 10_000;
    private static final double HOME_ADVANTAGE = 0.15;
    private static final Random RAND           = new Random();

    public static class SimulationResult {
        public final Prediction prediction;  // built from raw model probs
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

        double eloAdjustment = EloRatingSystem.expectedScore(home.getElo(), away.getElo());

        double homeAttack  = home.getHomeAttack();
        double homeDefense = home.getHomeDefense();
        double awayAttack  = away.getAwayAttack();
        double awayDefense = away.getAwayDefense();

        System.out.printf("[SIM] %s  homeAtt=%.2f homeDef=%.2f%n",
                home.getName(), homeAttack, homeDefense);
        System.out.printf("[SIM] %s  awayAtt=%.2f awayDef=%.2f%n",
                away.getName(), awayAttack, awayDefense);

        double lambdaHome = homeAttack
                * (1.0 / Math.max(awayDefense, 0.5))
                * (0.8 + eloAdjustment * 0.4)
                * (1.0 + HOME_ADVANTAGE);

        double lambdaAway = awayAttack
                * (1.0 / Math.max(homeDefense, 0.5))
                * (1.2 - eloAdjustment * 0.4);

        double h2hAdvantage = HeadToHeadAnalyzer.getAdvantage(home.getId(), away.getId());
        lambdaHome = Math.max(lambdaHome * (1.0 + h2hAdvantage), 0.1);
        lambdaAway = Math.max(lambdaAway * (1.0 - h2hAdvantage), 0.1);

        System.out.printf("[SIM] Lambda: %s=%.2f  %s=%.2f  (ELO=%.2f H2H=%.2f)%n",
                home.getName(), lambdaHome, away.getName(), lambdaAway,
                eloAdjustment, h2hAdvantage);
        System.out.printf("[SIM] Expected total goals: %.2f%n", lambdaHome + lambdaAway);

        int homeWins = 0, awayWins = 0, draws = 0;
        int over15   = 0, over25   = 0, over35 = 0;

        for (int i = 0; i < SIMULATIONS; i++) {
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

        // Predicted score from lambda (raw model, before blending)
        int predictedHome = (int) Math.round(lambdaHome);
        int predictedAway = (int) Math.round(lambdaAway);

        Prediction prediction = new Prediction(
                home.getName(), away.getName(),
                predictedHome, predictedAway,
                homeWinProb, awayWinProb, drawProb
        );

        return new SimulationResult(prediction,
                lambdaHome, lambdaAway,
                homeWinProb, drawProb, awayWinProb,
                over15Prob, over25Prob, over35Prob);
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