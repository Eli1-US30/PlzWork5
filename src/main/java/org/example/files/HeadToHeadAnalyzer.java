package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Analyses historical head-to-head results between two teams.
 * Parses football-data.org response format.
 */
public class HeadToHeadAnalyzer {

    public static double getAdvantage(int homeId, int awayId) {
        int homeWins = 0;
        int awayWins = 0;

        try {
            JSONArray matches = APIFootballClient.getHeadToHead(homeId, awayId);

            if (matches == null || matches.isEmpty()) return 0;

            for (int i = 0; i < matches.length(); i++) {
                JSONObject match = matches.getJSONObject(i);

                int hId = match.getJSONObject("homeTeam").getInt("id");

                JSONObject fullTime = match.getJSONObject("score").getJSONObject("fullTime");
                int homeGoals = fullTime.optInt("home", 0);
                int awayGoals = fullTime.optInt("away", 0);

                boolean homeWasHome = (hId == homeId);
                int teamGoals = homeWasHome ? homeGoals : awayGoals;
                int oppGoals  = homeWasHome ? awayGoals : homeGoals;

                if (teamGoals > oppGoals)      homeWins++;
                else if (oppGoals > teamGoals) awayWins++;
            }

        } catch (Exception e) {
            System.out.println("[WARN] H2H lookup failed, using 0 advantage.");
            return 0;
        }

        int total = homeWins + awayWins;
        if (total == 0) return 0;

        return 0.2 * (homeWins - awayWins) / (double) total;
    }
}