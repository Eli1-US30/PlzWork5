package org.example.files;

/**
 * Standard ELO rating system adapted for football.
 *
 * Seed values reflect the 2025/26 Premier League season.
 * Promoted teams: Leeds United, Burnley, Sunderland
 * Relegated teams: Leicester City, Ipswich Town, Southampton
 *
 * Seeds are starting estimates only — EloStore updates them
 * match by match throughout the season so they become
 * increasingly accurate as the season progresses.
 */
public class EloRatingSystem {

    private static final double K = 32.0;

    public static double update(double rating, double opponentRating, double result) {
        double expected = expectedScore(rating, opponentRating);
        return rating + K * (result - expected);
    }

    public static double expectedScore(double rating, double opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    public static double seedElo(String teamName) {
        return switch (teamName) {
            // Established PL clubs — seeded on recent form
            case "Arsenal FC"                   -> 1800;
            case "Manchester City FC"           -> 1780;
            case "Liverpool FC"                 -> 1820;
            case "Chelsea FC"                   -> 1720;
            case "Newcastle United FC"          -> 1700;
            case "Aston Villa FC"               -> 1680;
            case "Nottingham Forest FC"         -> 1660;
            case "Brighton & Hove Albion FC"    -> 1640;
            case "Brentford FC"                 -> 1620;
            case "Fulham FC"                    -> 1610;
            case "Crystal Palace FC"            -> 1580;
            case "Everton FC"                   -> 1560;
            case "West Ham United FC"           -> 1550;
            case "AFC Bournemouth"              -> 1545;
            case "Wolverhampton Wanderers FC"   -> 1530;
            case "Manchester United FC"         -> 1520;
            case "Tottenham Hotspur FC"         -> 1510;

            // Promoted clubs — seeded conservatively
            case "Leeds United FC"              -> 1490;
            case "Burnley FC"                   -> 1470;
            case "Sunderland AFC"               -> 1460;

            // Fallback for any unmapped team
            default                             -> 1500;
        };
    }
}