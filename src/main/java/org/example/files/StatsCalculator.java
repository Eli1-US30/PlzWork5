package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds a Team's stats from last 5 matches + FPL data.
 *
 * v4 addition — FPL STRENGTH RATING BLEND:
 *   FPL rates every team's attack and defense for home and away
 *   on a scale of roughly 1000-1350, updated weekly.
 *   We normalise these to a 0-3 goals-per-game scale and blend
 *   them with our match-derived home/away splits.
 *
 *   This is especially valuable early in the season when we only
 *   have 1-2 home or away matches to calculate from.
 *
 *   Blend: 50% match data + 30% FPL xG + 20% FPL strength rating
 *   (strength rating gets less weight as season progresses and
 *   we have more real match data)
 */
public class StatsCalculator {

    private static final double LEAGUE_AVG_ELO        = 1600.0;
    private static final double OPPONENT_WEIGHT_SCALE  = 0.3;
    private static final double MAX_FORM_NUDGE         = 30.0;
    private static final int    MIN_SPLIT_MATCHES      = 2;

    // FPL strength scale: 1000 = weakest, 1350 = strongest
    // Normalise to goals-per-game equivalent (0.5 to 2.5 range)
    private static final double FPL_STR_MIN   = 1000.0;
    private static final double FPL_STR_MAX   = 1350.0;
    private static final double GOALS_MIN     = 0.5;
    private static final double GOALS_MAX     = 2.5;

    public static Team buildTeam(int teamId, String teamName) {
        Team team = new Team(teamId, teamName);

        // --- ELO base ---
        double seedElo      = EloRatingSystem.seedElo(teamName);
        double histBonus    = TheSportsDBClient.getHistoricalEloBonus(teamName);
        double standingsAdj = StandingsClient.getStandingsEloAdjustment(teamName);
        double historicElo  = seedElo + histBonus;
        double currentElo   = seedElo + standingsAdj;
        double baseElo      = historicElo * 0.5 + currentElo * 0.5;

        // --- Last 5 matches ---
        JSONArray lastMatches = APIFootballClient.getLastMatches(teamId);

        if (lastMatches.isEmpty()) {
            System.out.println("[WARN] No match data for " + teamName + " — using defaults.");
            team.setElo(baseElo);
            return team;
        }

        int matchCount = lastMatches.length();

        double wGoalsFor = 0, wGoalsAgainst = 0, wForm = 0, wTotal = 0;
        double hGoalsFor = 0, hGoalsAgainst = 0, hTotal = 0; int hCount = 0;
        double aGoalsFor = 0, aGoalsAgainst = 0, aTotal = 0; int aCount = 0;

        for (int i = 0; i < matchCount; i++) {
            JSONObject fixture = lastMatches.getJSONObject(i);
            JSONObject fullTime = fixture.getJSONObject("score").getJSONObject("fullTime");

            int homeGoals = fullTime.optInt("home", 0);
            int awayGoals = fullTime.optInt("away", 0);
            int homeId    = fixture.getJSONObject("homeTeam").getInt("id");
            boolean wasHome = (homeId == teamId);

            int scored   = wasHome ? homeGoals : awayGoals;
            int conceded = wasHome ? awayGoals : homeGoals;

            double recencyWeight  = (matchCount - i);
            String opponentName   = wasHome
                    ? fixture.getJSONObject("awayTeam").getString("name")
                    : fixture.getJSONObject("homeTeam").getString("name");
            double opponentElo    = EloRatingSystem.seedElo(opponentName);
            double opponentWeight = 1.0 + OPPONENT_WEIGHT_SCALE *
                    (opponentElo / LEAGUE_AVG_ELO - 1.0);
            opponentWeight = Math.max(0.7, Math.min(1.5, opponentWeight));
            double combinedWeight = recencyWeight * opponentWeight;

            double points = scored > conceded ? 1.0 : scored == conceded ? 0.5 : 0.0;

            wGoalsFor += scored * combinedWeight;
            wGoalsAgainst += conceded * combinedWeight;
            wForm += points * combinedWeight;
            wTotal += combinedWeight;

            if (wasHome) {
                hGoalsFor += scored * combinedWeight;
                hGoalsAgainst += conceded * combinedWeight;
                hTotal += combinedWeight; hCount++;
            } else {
                aGoalsFor += scored * combinedWeight;
                aGoalsAgainst += conceded * combinedWeight;
                aTotal += combinedWeight; aCount++;
            }

            System.out.printf("[FORM] %s (%s) vs %-28s scored=%d conceded=%d comb=%.2f%n",
                    teamName, wasHome ? "H" : "A", opponentName,
                    scored, conceded, combinedWeight);
        }

        double attack  = wTotal > 0 ? wGoalsFor     / wTotal : 1.2;
        double defense = wTotal > 0 ? wGoalsAgainst / wTotal : 1.2;
        double form    = wTotal > 0 ? wForm          / wTotal : 0.5;

        // --- Feature 6: Per-team home advantage ---
        // Calculate from home vs away win rates in recent matches.
        // Requires at least 2 home AND 2 away matches to be meaningful.
        // Stored in Team so Simulator can use it instead of the global constant.
        if (hCount >= MIN_SPLIT_MATCHES && aCount >= MIN_SPLIT_MATCHES) {
            double homePoints = hTotal > 0 ? (hGoalsFor > 0 ? hGoalsFor / hTotal : 0) : 0;
            // Simpler: use raw home win rate vs away win rate from weighted form
            double homeWinRate = hTotal > 0
                    ? (hGoalsFor / hTotal > hGoalsAgainst / hTotal ? 0.7 : 0.4) : 0.5;
            double awayWinRate = aTotal > 0
                    ? (aGoalsFor / aTotal > aGoalsAgainst / aTotal ? 0.7 : 0.4) : 0.5;

            // Home advantage = how much better they perform at home vs away
            // Scaled to a 0.05-0.25 range (global default is 0.15)
            double rawHomeAdv = homeWinRate - awayWinRate + 0.15;
            double homeAdv    = Math.max(0.05, Math.min(0.25, rawHomeAdv));
            team.setHomeAdvantage(homeAdv);
            System.out.printf("[HOME-ADV] %s calculated homeAdv=%.3f%n",
                    teamName, homeAdv);
        }

        // --- FPL data ---
        double xgPerGame  = FPLDataCache.getTeamXGPerGame(teamName);
        double xgcPerGame = FPLDataCache.getTeamXGConcededPerGame(teamName);

        // FPL strength ratings normalised to goals-per-game scale
        double fplAttHome  = normaliseStrength(FPLDataCache.getStrengthAttackHome(teamName));
        double fplAttAway  = normaliseStrength(FPLDataCache.getStrengthAttackAway(teamName));
        double fplDefHome  = normaliseStrength(FPLDataCache.getStrengthDefenceHome(teamName));
        double fplDefAway  = normaliseStrength(FPLDataCache.getStrengthDefenceAway(teamName));

        System.out.printf("[FPL-STR] %s normAttH=%.2f normAttA=%.2f normDefH=%.2f normDefA=%.2f%n",
                teamName, fplAttHome, fplAttAway, fplDefHome, fplDefAway);

        // Feature 3: key player injury multiplier
        double keyPlayerMult = FPLDataCache.getKeyPlayerXGMultiplier(teamName);

        // --- Combined fallback (xG blend) ---
        double blendedXG    = xgPerGame > 0 ? xgPerGame * keyPlayerMult : xgPerGame;
        double finalAttack  = blendedXG  > 0 ? (attack  * 0.4) + (blendedXG  * 0.6) : attack;
        double finalDefense = xgcPerGame > 0 ? (defense * 0.4) + (xgcPerGame * 0.6) : defense;

        team.setAttack(Math.max(finalAttack, 0.3));
        team.setDefense(Math.max(finalDefense, 0.3));
        team.setForm(form);

        // --- Home split ---
        if (hCount >= MIN_SPLIT_MATCHES) {
            double rawHomeAtt = hGoalsFor / hTotal;
            double rawHomeDef = hGoalsAgainst / hTotal;
            // Blend: 50% match data + 30% xG + 20% FPL strength
            double homeAtt = blendThree(rawHomeAtt, xgPerGame,  fplAttHome,  xgPerGame > 0);
            double homeDef = blendThree(rawHomeDef, xgcPerGame, fplDefHome, xgcPerGame > 0);
            team.setHomeAttack(Math.max(homeAtt, 0.3));
            team.setHomeDefense(Math.max(homeDef, 0.3));
            System.out.printf("[SPLIT] %s HOME att=%.2f def=%.2f (%d matches)%n",
                    teamName, homeAtt, homeDef, hCount);
        } else {
            // Not enough match data — use xG + FPL strength only
            double homeAtt = xgPerGame > 0
                    ? (xgPerGame * 0.6) + (fplAttHome * 0.4) : fplAttHome;
            double homeDef = xgcPerGame > 0
                    ? (xgcPerGame * 0.6) + (fplDefHome * 0.4) : fplDefHome;
            team.setHomeAttack(Math.max(homeAtt, 0.3));
            team.setHomeDefense(Math.max(homeDef, 0.3));
            System.out.printf("[SPLIT] %s HOME thin data — using xG+FPL att=%.2f def=%.2f%n",
                    teamName, homeAtt, homeDef);
        }

        // --- Away split ---
        if (aCount >= MIN_SPLIT_MATCHES) {
            double rawAwayAtt = aGoalsFor / aTotal;
            double rawAwayDef = aGoalsAgainst / aTotal;
            double awayAtt = blendThree(rawAwayAtt, xgPerGame,  fplAttAway,  xgPerGame > 0);
            double awayDef = blendThree(rawAwayDef, xgcPerGame, fplDefAway, xgcPerGame > 0);
            team.setAwayAttack(Math.max(awayAtt, 0.3));
            team.setAwayDefense(Math.max(awayDef, 0.3));
            System.out.printf("[SPLIT] %s AWAY att=%.2f def=%.2f (%d matches)%n",
                    teamName, awayAtt, awayDef, aCount);
        } else {
            double awayAtt = xgPerGame > 0
                    ? (xgPerGame * 0.6) + (fplAttAway * 0.4) : fplAttAway;
            double awayDef = xgcPerGame > 0
                    ? (xgcPerGame * 0.6) + (fplDefAway * 0.4) : fplDefAway;
            team.setAwayAttack(Math.max(awayAtt, 0.3));
            team.setAwayDefense(Math.max(awayDef, 0.3));
            System.out.printf("[SPLIT] %s AWAY thin data — using xG+FPL att=%.2f def=%.2f%n",
                    teamName, awayAtt, awayDef);
        }

        // --- ELO with form nudge ---
        double formNudge = (form - 0.5) * 2.0 * MAX_FORM_NUDGE;
        double finalElo  = baseElo + formNudge;
        team.setElo(finalElo);
        System.out.printf("[ELO] %s base=%.0f formNudge=%.1f FINAL=%.0f%n",
                teamName, baseElo, formNudge, finalElo);

        return team;
    }

    /**
     * Blends match data, xG, and FPL strength rating.
     * Weights: 50% match + 30% xG + 20% FPL strength
     * If xG unavailable: 70% match + 30% FPL strength
     */
    private static double blendThree(double matchVal, double xgVal,
                                     double fplVal, boolean hasXG) {
        if (hasXG) {
            return (matchVal * 0.50) + (xgVal * 0.30) + (fplVal * 0.20);
        } else {
            return (matchVal * 0.70) + (fplVal * 0.30);
        }
    }

    /**
     * Normalises FPL strength rating (1000-1350) to
     * goals-per-game equivalent (0.5-2.5).
     */
    private static double normaliseStrength(int strength) {
        double clamped = Math.max(FPL_STR_MIN, Math.min(FPL_STR_MAX, strength));
        return GOALS_MIN + ((clamped - FPL_STR_MIN) / (FPL_STR_MAX - FPL_STR_MIN))
                * (GOALS_MAX - GOALS_MIN);
    }

}