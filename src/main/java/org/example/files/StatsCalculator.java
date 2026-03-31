package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Team's stats from their last 5 completed matches.
 *
 * v3 additions:
 * - Home/away splits for attack and defense
 *   Each match is bucketed into home or away based on where the team played.
 *   Recency + opponent ELO weighting applied within each bucket.
 *   If a bucket has fewer than 2 matches, falls back to combined rating
 *   so we never predict from a single data point.
 *
 * Existing:
 * - Recency decay (most recent = highest weight)
 * - Opponent ELO weighting
 * - FPL xG/xGC blend (60/40)
 * - Form nudge on ELO (±30)
 * - ELO blend: 50% historic + 50% current standings
 */
public class StatsCalculator {

    private static final double LEAGUE_AVG_ELO        = 1600.0;
    private static final double OPPONENT_WEIGHT_SCALE  = 0.3;
    private static final double MAX_FORM_NUDGE         = 30.0;
    // Minimum matches in a home/away bucket before we trust the split
    private static final int    MIN_SPLIT_MATCHES      = 2;

    public static Team buildTeam(int teamId, String teamName) {
        Team team = new Team(teamId, teamName);

        // --- ELO base ---
        double seedElo      = EloRatingSystem.seedElo(teamName);
        double histBonus    = TheSportsDBClient.getHistoricalEloBonus(teamName);
        double standingsAdj = StandingsClient.getStandingsEloAdjustment(teamName);

        double historicElo  = seedElo + histBonus;
        double currentElo   = seedElo + standingsAdj;
        double baseElo      = historicElo * 0.5 + currentElo * 0.5;

        // --- Fetch last 5 matches ---
        JSONArray lastMatches = APIFootballClient.getLastMatches(teamId);

        if (lastMatches.isEmpty()) {
            System.out.println("[WARN] No match data for " + teamName + " — using defaults.");
            team.setElo(baseElo);
            return team;
        }

        int matchCount = lastMatches.length();

        // Combined accumulators
        double wGoalsFor = 0, wGoalsAgainst = 0, wForm = 0, wTotal = 0;

        // Home split accumulators
        double hGoalsFor = 0, hGoalsAgainst = 0, hTotal = 0;
        int    hCount    = 0;

        // Away split accumulators
        double aGoalsFor = 0, aGoalsAgainst = 0, aTotal = 0;
        int    aCount    = 0;

        for (int i = 0; i < matchCount; i++) {
            JSONObject fixture = lastMatches.getJSONObject(i);

            JSONObject fullTime = fixture
                    .getJSONObject("score")
                    .getJSONObject("fullTime");

            int homeGoals = fullTime.optInt("home", 0);
            int awayGoals = fullTime.optInt("away", 0);

            int     homeId  = fixture.getJSONObject("homeTeam").getInt("id");
            boolean wasHome = (homeId == teamId);

            int scored   = wasHome ? homeGoals : awayGoals;
            int conceded = wasHome ? awayGoals : homeGoals;

            // Recency: index 0 = most recent from API → highest weight
            double recencyWeight = (matchCount - i);

            // Opponent ELO weight
            String opponentName = wasHome
                    ? fixture.getJSONObject("awayTeam").getString("name")
                    : fixture.getJSONObject("homeTeam").getString("name");

            double opponentElo    = EloRatingSystem.seedElo(opponentName);
            double eloRatio       = opponentElo / LEAGUE_AVG_ELO;
            double opponentWeight = 1.0 + OPPONENT_WEIGHT_SCALE * (eloRatio - 1.0);
            opponentWeight = Math.max(0.7, Math.min(1.5, opponentWeight));

            double combinedWeight = recencyWeight * opponentWeight;

            double points = (scored > conceded) ? 1.0 : (scored == conceded) ? 0.5 : 0.0;

            // Combined accumulators
            wGoalsFor     += scored   * combinedWeight;
            wGoalsAgainst += conceded * combinedWeight;
            wForm         += points   * combinedWeight;
            wTotal        += combinedWeight;

            // Home/away split accumulators
            if (wasHome) {
                hGoalsFor     += scored   * combinedWeight;
                hGoalsAgainst += conceded * combinedWeight;
                hTotal        += combinedWeight;
                hCount++;
            } else {
                aGoalsFor     += scored   * combinedWeight;
                aGoalsAgainst += conceded * combinedWeight;
                aTotal        += combinedWeight;
                aCount++;
            }

            System.out.printf("[FORM] %s (%s) vs %-28s scored=%d conceded=%d rec=%.0f oppW=%.2f comb=%.2f%n",
                    teamName, wasHome ? "H" : "A", opponentName,
                    scored, conceded, recencyWeight, opponentWeight, combinedWeight);
        }

        // --- Combined ratings ---
        double attack  = wTotal > 0 ? wGoalsFor     / wTotal : 1.2;
        double defense = wTotal > 0 ? wGoalsAgainst / wTotal : 1.2;
        double form    = wTotal > 0 ? wForm          / wTotal : 0.5;

        // --- Home split ratings (if enough data) ---
        if (hCount >= MIN_SPLIT_MATCHES) {
            double rawHomeAttack  = hGoalsFor     / hTotal;
            double rawHomeDefense = hGoalsAgainst / hTotal;

            // Blend home split with FPL xG (xG is season-wide so applies to both)
            double xgPerGame  = FPLDataCache.getTeamXGPerGame(teamName);
            double xgcPerGame = FPLDataCache.getTeamXGConcededPerGame(teamName);

            double homeAttack  = xgPerGame  > 0 ? (rawHomeAttack  * 0.4) + (xgPerGame  * 0.6) : rawHomeAttack;
            double homeDefense = xgcPerGame > 0 ? (rawHomeDefense * 0.4) + (xgcPerGame * 0.6) : rawHomeDefense;

            team.setHomeAttack(Math.max(homeAttack, 0.3));
            team.setHomeDefense(Math.max(homeDefense, 0.3));

            System.out.printf("[SPLIT] %s HOME  att=%.2f def=%.2f (from %d matches)%n",
                    teamName, homeAttack, homeDefense, hCount);
        } else {
            System.out.printf("[SPLIT] %s HOME  insufficient data (%d matches) — using combined%n",
                    teamName, hCount);
        }

        // --- Away split ratings (if enough data) ---
        if (aCount >= MIN_SPLIT_MATCHES) {
            double rawAwayAttack  = aGoalsFor     / aTotal;
            double rawAwayDefense = aGoalsAgainst / aTotal;

            double xgPerGame  = FPLDataCache.getTeamXGPerGame(teamName);
            double xgcPerGame = FPLDataCache.getTeamXGConcededPerGame(teamName);

            double awayAttack  = xgPerGame  > 0 ? (rawAwayAttack  * 0.4) + (xgPerGame  * 0.6) : rawAwayAttack;
            double awayDefense = xgcPerGame > 0 ? (rawAwayDefense * 0.4) + (xgcPerGame * 0.6) : rawAwayDefense;

            team.setAwayAttack(Math.max(awayAttack, 0.3));
            team.setAwayDefense(Math.max(awayDefense, 0.3));

            System.out.printf("[SPLIT] %s AWAY  att=%.2f def=%.2f (from %d matches)%n",
                    teamName, awayAttack, awayDefense, aCount);
        } else {
            System.out.printf("[SPLIT] %s AWAY  insufficient data (%d matches) — using combined%n",
                    teamName, aCount);
        }

        // --- Combined fallback (also used if splits kick in as base for form) ---
        double xgPerGame  = FPLDataCache.getTeamXGPerGame(teamName);
        double xgcPerGame = FPLDataCache.getTeamXGConcededPerGame(teamName);

        double finalAttack  = xgPerGame  > 0 ? (attack  * 0.4) + (xgPerGame  * 0.6) : attack;
        double finalDefense = xgcPerGame > 0 ? (defense * 0.4) + (xgcPerGame * 0.6) : defense;

        team.setAttack(Math.max(finalAttack, 0.3));
        team.setDefense(Math.max(finalDefense, 0.3));
        team.setForm(form);

        // --- Form nudge on ELO ---
        double formNudge = (form - 0.5) * 2.0 * MAX_FORM_NUDGE;
        double finalElo  = baseElo + formNudge;

        team.setElo(finalElo);
        System.out.printf("[ELO] %s  seed=%.0f  hist=%.0f  standings=%.0f  base=%.0f  formNudge=%.1f  FINAL=%.0f%n",
                teamName, seedElo, histBonus, standingsAdj, baseElo, formNudge, finalElo);

        return team;
    }
}