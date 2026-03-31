package org.example.files;

public class Prediction {

    private final String homeTeam;
    private final String awayTeam;
    private final int homeGoals;
    private final int awayGoals;
    private final double homeWinProb;
    private final double awayWinProb;
    private final double drawProb;

    public Prediction(String homeTeam, String awayTeam,
                      int homeGoals, int awayGoals,
                      double homeWinProb, double awayWinProb, double drawProb) {
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.homeGoals = homeGoals;
        this.awayGoals = awayGoals;
        this.homeWinProb = homeWinProb;
        this.awayWinProb = awayWinProb;
        this.drawProb = drawProb;
    }

    public String getHomeTeam() { return homeTeam; }
    public String getAwayTeam() { return awayTeam; }
    public int getHomeGoals() { return homeGoals; }
    public int getAwayGoals() { return awayGoals; }
    public double getHomeWinProb() { return homeWinProb; }
    public double getAwayWinProb() { return awayWinProb; }
    public double getDrawProb() { return drawProb; }

    public String getOutcome() {
        if (homeWinProb >= awayWinProb && homeWinProb >= drawProb) return homeTeam + " Win";
        if (awayWinProb >= homeWinProb && awayWinProb >= drawProb) return awayTeam + " Win";
        return "Draw";
    }

    @Override
    public String toString() {
        return String.format(
                "%s vs %s\n" +
                "Predicted Score : %d - %d\n" +
                "Home Win        : %.1f%%\n" +
                "Draw            : %.1f%%\n" +
                "Away Win        : %.1f%%\n" +
                "Most Likely     : %s",
                homeTeam, awayTeam,
                homeGoals, awayGoals,
                homeWinProb, drawProb, awayWinProb,
                getOutcome()
        );
    }
}
