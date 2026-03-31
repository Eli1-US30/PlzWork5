package org.example.files;

/**
 * Standard ELO rating system adapted for football.
 *
 * K-factor of 32 means a single match can shift a rating by up to 32 points.
 * The expected score formula accounts for the rating gap between teams.
 */
public class EloRatingSystem {

    private static final double K = 32.0;

    /**
     * Returns the updated ELO rating for a team after a match result.
     *
     * @param rating         current ELO of the team
     * @param opponentRating current ELO of the opponent
     * @param result         1.0 = win, 0.5 = draw, 0.0 = loss
     * @return new ELO rating
     */
    public static double update(double rating, double opponentRating, double result) {
        double expected = expectedScore(rating, opponentRating);
        return rating + K * (result - expected);
    }

    /**
     * Returns the probability that `rating` beats `opponentRating`.
     * Used in Simulator to weight lambdas.
     */
    public static double expectedScore(double rating, double opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    /**
     * Seed ELO ratings for Premier League clubs based on recent seasons.
     * These are starting estimates — they update match by match once the app runs.
     */
    public static double seedElo(String teamName) {
        return switch (teamName) {
            case "Manchester City FC"           -> 1850;
            case "Arsenal FC"                   -> 1780;
            case "Liverpool FC"                 -> 1800;
            case "Chelsea FC"                   -> 1720;
            case "Manchester United FC"         -> 1700;
            case "Tottenham Hotspur FC"         -> 1690;
            case "Newcastle United FC"          -> 1670;
            case "Aston Villa FC"               -> 1660;
            case "Brighton & Hove Albion FC"    -> 1640;
            case "West Ham United FC"           -> 1620;
            case "Brentford FC"                 -> 1610;
            case "Fulham FC"                    -> 1590;
            case "Crystal Palace FC"            -> 1570;
            case "Wolverhampton Wanderers FC"   -> 1555;
            case "Everton FC"                   -> 1545;
            case "Nottingham Forest FC"         -> 1530;
            case "AFC Bournemouth"              -> 1520;
            case "Leicester City FC"            -> 1500;
            case "Ipswich Town FC"              -> 1480;
            case "Southampton FC"               -> 1460;
            default                             -> 1500;
        };
    }
}

