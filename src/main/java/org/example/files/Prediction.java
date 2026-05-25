package org.example.files;

public class Prediction {

    private final String homeTeam;
    private final String awayTeam;
    private final int    homeGoals;
    private final int    awayGoals;
    private final double homeWinProb;
    private final double awayWinProb;
    private final double drawProb;

    public Prediction(String homeTeam, String awayTeam,
                      int homeGoals, int awayGoals,
                      double homeWinProb, double awayWinProb, double drawProb) {
        this.homeTeam    = homeTeam;
        this.awayTeam    = awayTeam;
        this.homeGoals   = homeGoals;
        this.awayGoals   = awayGoals;
        this.homeWinProb = homeWinProb;
        this.awayWinProb = awayWinProb;
        this.drawProb    = drawProb;
    }

    public String getHomeTeam()    { return homeTeam; }
    public String getAwayTeam()    { return awayTeam; }
    public int    getHomeGoals()   { return homeGoals; }
    public int    getAwayGoals()   { return awayGoals; }
    public double getHomeWinProb() { return homeWinProb; }
    public double getAwayWinProb() { return awayWinProb; }
    public double getDrawProb()    { return drawProb; }

    /**
     * Outcome is derived from the PREDICTED SCORE, not the highest probability.
     *
     * Fix: previously this used homeWinProb/awayWinProb/drawProb to decide
     * the outcome label. This caused a mismatch where the predicted score
     * showed 1-1 (a draw) but the outcome said "Liverpool FC Win" because
     * Liverpool had the highest win probability.
     *
     * Now: if we predict homeGoals == awayGoals → "Draw"
     *      if we predict homeGoals >  awayGoals → home team win
     *      if we predict awayGoals >  homeGoals → away team win
     *
     * The probabilities are still shown separately in the Telegram message
     * and are unchanged — this only affects the outcome label.
     */
    public String getOutcome() {
        if (homeGoals > awayGoals) return homeTeam + " Win";
        if (awayGoals > homeGoals) return awayTeam + " Win";
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