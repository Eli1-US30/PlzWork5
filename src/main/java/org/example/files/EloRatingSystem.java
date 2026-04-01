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
 *
 * Seeds calibrated against actual 2025/26 table (GW31 approx):
 *   1. Arsenal 70pts   2. Man City 61pts   3. Man Utd 55pts
 *   4. Aston Villa 54pts   5. Liverpool 49pts   6. Chelsea 48pts
 *   7. Brentford 46pts   8. Everton 46pts   9. Fulham 44pts
 *  10. Brighton 43pts  11. Sunderland 43pts  12. Newcastle 42pts
 *  13. Bournemouth 42pts  14. Crystal Palace 39pts  15. Leeds 33pts
 *  16. Nott'm Forest 32pts  17. Tottenham 30pts  18. West Ham 29pts
 *  19. Burnley 20pts  20. Wolves 17pts
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
            // Top of table — strong this season
            case "Arsenal FC"                   -> 1810;  // 1st, 70pts
            case "Manchester City FC"           -> 1790;  // 2nd, historical powerhouse
            case "Manchester United FC"         -> 1680;  // 3rd, massively outperforming recent seed
            case "Aston Villa FC"               -> 1690;  // 4th, consistent top-half performer
            case "Liverpool FC"                 -> 1760;  // 5th, underperforming pedigree this season
            case "Chelsea FC"                   -> 1710;  // 6th, solid mid-upper table

            // Mid-table — performing around their level
            case "Brentford FC"                 -> 1620;  // 7th
            case "Everton FC"                   -> 1580;  // 8th, better than expected
            case "Fulham FC"                    -> 1610;  // 9th
            case "Brighton & Hove Albion FC"    -> 1640;  // 10th, quality squad
            case "Sunderland AFC"               -> 1500;  // 11th, promoted but performing well
            case "Newcastle United FC"          -> 1700;  // 12th, underperforming their quality
            case "AFC Bournemouth"              -> 1545;  // 13th
            case "Crystal Palace FC"            -> 1580;  // 14th

            // Lower table — struggling
            case "Leeds United FC"              -> 1490;  // 15th, promoted
            case "Nottingham Forest FC"         -> 1590;  // 16th, dropping from last season's 7th
            case "Tottenham Hotspur FC"         -> 1500;  // 17th, badly underperforming
            case "West Ham United FC"           -> 1550;  // 18th, struggling
            case "Burnley FC"                   -> 1470;  // 19th, promoted and fighting
            case "Wolverhampton Wanderers FC"   -> 1480;  // 20th, bottom

            // Fallback for any unmapped team
            default                             -> 1500;
        };
    }
}