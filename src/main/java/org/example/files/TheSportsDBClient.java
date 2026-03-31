package org.example.files;

import java.util.HashMap;
import java.util.Map;

/**
 * Historical Premier League finishing positions for last 5 seasons.
 * Hardcoded for reliability — update once per season end.
 * Used to calculate historical ELO bonus.
 *
 * Seasons: 2019-20, 2020-21, 2021-22, 2022-23, 2023-24
 */
public class TheSportsDBClient {

    // football-data.org team name -> average finishing position over 5 seasons
    // Teams not present (promoted clubs) get 0 bonus automatically
    private static final Map<String, Double> AVG_POSITIONS = new HashMap<>();

    static {
        // Positions per season: 20/21, 21/22, 22/23, 23/24, 24/25
        // 0 = not in PL that season (excluded from average)
        //                                              20/21 21/22 22/23 23/24 24/25
        AVG_POSITIONS.put("Manchester City FC",    avg(  1,   1,   1,   1,   3));
        AVG_POSITIONS.put("Liverpool FC",          avg(  3,   2,   5,   3,   1));
        AVG_POSITIONS.put("Arsenal FC",            avg(  8,   5,   2,   2,   2));
        AVG_POSITIONS.put("Chelsea FC",            avg(  4,   3,  12,   6,   4));
        AVG_POSITIONS.put("Tottenham Hotspur FC",  avg(  7,   4,   8,   5,  17));
        AVG_POSITIONS.put("Manchester United FC",  avg(  2,   6,   3,   8,  15));
        AVG_POSITIONS.put("Newcastle United FC",   avg( 12,  11,   4,   7,   5));
        AVG_POSITIONS.put("Aston Villa FC",        avg( 11,  14,   7,   4,   6));
        AVG_POSITIONS.put("Brighton & Hove Albion FC", avg(16,  9,   6,  11,   8));
        AVG_POSITIONS.put("West Ham United FC",    avg(  6,   7,  14,   9,  14));
        AVG_POSITIONS.put("Brentford FC",          avg(  0,  13,   9,  16,  10));
        AVG_POSITIONS.put("Fulham FC",             avg(  0,   0,  10,  13,  11));
        AVG_POSITIONS.put("Crystal Palace FC",     avg( 14,  12,  11,  10,  12));
        AVG_POSITIONS.put("Wolverhampton Wanderers FC", avg(13, 10,  13,  14,  16));
        AVG_POSITIONS.put("Everton FC",            avg( 10,  16,  17,  15,  13));
        AVG_POSITIONS.put("Nottingham Forest FC",  avg(  0,   0,  16,  17,   7));
        AVG_POSITIONS.put("AFC Bournemouth",       avg(  0,   0,  15,  12,   9));
        AVG_POSITIONS.put("Leicester City FC",     avg(  5,   8,  18,   0,   0));
        AVG_POSITIONS.put("Southampton FC",        avg( 15,  15,  20,   0,   0));
        AVG_POSITIONS.put("Ipswich Town FC",       avg(  0,   0,   0,   0,   0));
    }

    /**
     * Calculates average of provided positions.
     * 0 means team was not in PL that season — excluded from average.
     */
    private static double avg(int... positions) {
        int total = 0;
        int count = 0;
        for (int pos : positions) {
            if (pos > 0) {
                total += pos;
                count++;
            }
        }
        return count == 0 ? 20.0 : (double) total / count;
    }

    /**
     * Converts average finishing position to ELO bonus.
     * 1st place avg  = +200 ELO
     * 10th place avg = +100 ELO
     * 20th place avg = 0 ELO
     * Not in data (newly promoted) = 0 ELO bonus
     */
    public static double getHistoricalEloBonus(String fdTeamName) {
        Double avgPos = AVG_POSITIONS.get(fdTeamName);

        if (avgPos == null) {
            System.out.println("[TSDB] No historical data for: " + fdTeamName);
            return 0;
        }

        double bonus = Math.max(0, (21 - avgPos) * (200.0 / 20.0));
        System.out.printf("[TSDB] %s avgPos=%.1f eloBonus=%.0f%n",
                fdTeamName, avgPos, bonus);
        return bonus;
    }

    // Keep this so Main.java doesn't break
    public static void preload() {
        System.out.println("[TSDB] Historical standings loaded (hardcoded)");
    }
}