package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds team stats from FPL bootstrap and player history.
 * Updated for 2025/26 PL season:
 *   IN:  Leeds United, Burnley, Sunderland
 *   OUT: Leicester City, Ipswich Town, Southampton
 */
public class FPLDataCache {

    private static final Map<Integer, Integer> teamMatchesPlayed  = new HashMap<>();
    private static final Map<Integer, String>  fplTeamNames       = new HashMap<>();
    private static final Map<String, Integer>  nameToFplId        = new HashMap<>();
    private static final Map<Integer, Double>  teamXG             = new HashMap<>();
    private static final Map<Integer, Double>  teamXGConceded     = new HashMap<>();

    private static final Map<Integer, Integer> strengthAttackHome  = new HashMap<>();
    private static final Map<Integer, Integer> strengthAttackAway  = new HashMap<>();
    private static final Map<Integer, Integer> strengthDefenceHome = new HashMap<>();
    private static final Map<Integer, Integer> strengthDefenceAway = new HashMap<>();

    private static boolean initialized = false;
    private static final int RECENT_FIXTURES = 5;

    // Feature 3: key player injury weighting
    // Stores % of team xG contributed by the top scorer
    // If they are unavailable, we apply a penalty to team xG
    private static final Map<Integer, Double> teamTopScorerXGShare = new HashMap<>();
    private static final Map<Integer, Boolean> topScorerAvailable  = new HashMap<>();

    // football-data.org name -> FPL short name
    // Updated for 2025/26 season
    private static final Map<String, String> NAME_BRIDGE = new HashMap<>();
    static {
        NAME_BRIDGE.put("Arsenal FC",                   "Arsenal");
        NAME_BRIDGE.put("Manchester City FC",           "Man City");
        NAME_BRIDGE.put("Liverpool FC",                 "Liverpool");
        NAME_BRIDGE.put("Chelsea FC",                   "Chelsea");
        NAME_BRIDGE.put("Newcastle United FC",          "Newcastle");
        NAME_BRIDGE.put("Aston Villa FC",               "Aston Villa");
        NAME_BRIDGE.put("Nottingham Forest FC",         "Nott'm Forest");
        NAME_BRIDGE.put("Brighton & Hove Albion FC",    "Brighton");
        NAME_BRIDGE.put("Brentford FC",                 "Brentford");
        NAME_BRIDGE.put("Fulham FC",                    "Fulham");
        NAME_BRIDGE.put("Crystal Palace FC",            "Crystal Palace");
        NAME_BRIDGE.put("Everton FC",                   "Everton");
        NAME_BRIDGE.put("West Ham United FC",           "West Ham");
        NAME_BRIDGE.put("AFC Bournemouth",              "Bournemouth");
        NAME_BRIDGE.put("Wolverhampton Wanderers FC",   "Wolves");
        NAME_BRIDGE.put("Manchester United FC",         "Man Utd");
        NAME_BRIDGE.put("Tottenham Hotspur FC",         "Spurs");
        // Promoted 2025/26
        NAME_BRIDGE.put("Leeds United FC",              "Leeds");
        NAME_BRIDGE.put("Burnley FC",                   "Burnley");
        NAME_BRIDGE.put("Sunderland AFC",               "Sunderland");
    }

    public static void initialize() {
        if (initialized) return;

        JSONObject bootstrap = FPLClient.getBootstrapStatic();
        if (bootstrap == null) {
            System.out.println("[FPL] Cache init failed — no bootstrap data");
            return;
        }

        // Step 1: current gameweek
        int currentGameweek = 28;
        try {
            JSONArray events = bootstrap.getJSONArray("events");
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.optBoolean("is_current", false) ||
                        event.optBoolean("is_next", false)) {
                    currentGameweek = event.getInt("id");
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("[FPL] Could not determine gameweek, defaulting to 28");
        }
        System.out.println("[FPL] Current gameweek: " + currentGameweek);

        // Step 2: build team maps and FPL strength ratings
        JSONArray teams = bootstrap.getJSONArray("teams");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.getJSONObject(i);
            int    id   = team.getInt("id");
            String name = team.getString("name");
            fplTeamNames.put(id, name);
            teamMatchesPlayed.put(id, Math.max(currentGameweek - 1, 1));

            strengthAttackHome.put(id,  team.optInt("strength_attack_home",  1200));
            strengthAttackAway.put(id,  team.optInt("strength_attack_away",  1200));
            strengthDefenceHome.put(id, team.optInt("strength_defence_home", 1200));
            strengthDefenceAway.put(id, team.optInt("strength_defence_away", 1200));

            System.out.printf("[FPL-STR] %-20s attH=%d attA=%d defH=%d defA=%d%n",
                    name,
                    strengthAttackHome.get(id), strengthAttackAway.get(id),
                    strengthDefenceHome.get(id), strengthDefenceAway.get(id));
        }

        // Step 3: name bridge
        for (Map.Entry<String, String> entry : NAME_BRIDGE.entrySet()) {
            String fdName  = entry.getKey();
            String fplName = entry.getValue();
            for (Map.Entry<Integer, String> fplEntry : fplTeamNames.entrySet()) {
                if (fplEntry.getValue().equalsIgnoreCase(fplName)) {
                    nameToFplId.put(fdName, fplEntry.getKey());
                    break;
                }
            }
        }

        // Step 4: process players
        boolean lineupsAvailable = FPLLineupClient.isLineupsLoaded();
        System.out.println("[FPL] Using confirmed lineups: " + lineupsAvailable);

        Map<Integer, Double> gkBestMinutes   = new HashMap<>();
        Map<Integer, Double> gkBestXGCRate   = new HashMap<>();
        Map<Integer, Double> gkSecondMinutes = new HashMap<>();
        Map<Integer, Double> gkSecondXGCRate = new HashMap<>();

        JSONArray players = bootstrap.getJSONArray("elements");
        for (int i = 0; i < players.length(); i++) {
            JSONObject player  = players.getJSONObject(i);
            int    teamId      = player.getInt("team");
            int    playerId    = player.getInt("id");
            String status      = player.getString("status");
            int    chance      = player.optInt("chance_of_playing_next_round", 100);
            int    elementType = player.getInt("element_type");
            int    minutes     = player.optInt("minutes", 0);

            if (status.equals("i") || status.equals("s") || chance == 0) continue;
            if (lineupsAvailable && !FPLLineupClient.isConfirmedStarter(playerId)) continue;

            double availability = lineupsAvailable ? 1.0 : (chance / 100.0);
            double xg  = getRecentXG(playerId);
            double xgc = elementType == 1 ? getRecentXGC(playerId) : 0;
            int played = Math.min(RECENT_FIXTURES,
                    teamMatchesPlayed.getOrDefault(teamId, 1));

            if (elementType == 1) {
                double effectiveMinutes = minutes * availability;
                double xgcPerGame = played > 0 ? xgc / played : 0;
                double bestMins = gkBestMinutes.getOrDefault(teamId, -1.0);

                if (effectiveMinutes > bestMins) {
                    if (bestMins >= 0) {
                        gkSecondMinutes.put(teamId, bestMins);
                        gkSecondXGCRate.put(teamId,
                                gkBestXGCRate.getOrDefault(teamId, 0.0));
                    }
                    gkBestMinutes.put(teamId, effectiveMinutes);
                    gkBestXGCRate.put(teamId, xgcPerGame);
                } else if (effectiveMinutes >
                        gkSecondMinutes.getOrDefault(teamId, -1.0)) {
                    gkSecondMinutes.put(teamId, effectiveMinutes);
                    gkSecondXGCRate.put(teamId, xgcPerGame);
                }
            } else {
                teamXG.merge(teamId, xg * availability, Double::sum);

                // Feature 3: track top scorer per team for key player weighting
                double weightedXG = xg * availability;
                double currentTop = teamTopScorerXGShare.getOrDefault(teamId, 0.0);
                if (weightedXG > currentTop) {
                    teamTopScorerXGShare.put(teamId, weightedXG);
                    // Available if chance > 0 and not injured/suspended
                    topScorerAvailable.put(teamId,
                            !status.equals("i") && !status.equals("s") && chance > 0);
                }
            }
        }

        // Step 5: finalise GK xGC
        for (Integer teamId : fplTeamNames.keySet()) {
            double bestMins   = gkBestMinutes.getOrDefault(teamId, 0.0);
            double bestRate   = gkBestXGCRate.getOrDefault(teamId, -1.0);
            double secondMins = gkSecondMinutes.getOrDefault(teamId, 0.0);
            double secondRate = gkSecondXGCRate.getOrDefault(teamId, -1.0);

            if (bestRate < 0) continue;

            double finalXGC;
            if (secondRate >= 0 && bestMins > 0 && (secondMins / bestMins) >= 0.8) {
                double total = bestMins + secondMins;
                finalXGC = (bestRate * bestMins + secondRate * secondMins) / total;
                System.out.printf("[FPL-GK] %-20s blended → xGC/g=%.2f%n",
                        fplTeamNames.get(teamId), finalXGC);
            } else {
                finalXGC = bestRate;
                System.out.printf("[FPL-GK] %-20s starter → xGC/g=%.2f%n",
                        fplTeamNames.get(teamId), finalXGC);
            }
            teamXGConceded.put(teamId, finalXGC);
        }

        System.out.println("[FPL] Cache built for " + fplTeamNames.size() + " teams");
        initialized = true;
    }

    private static double getRecentXG(int playerId) {
        return fetchRecentStat(playerId, "expected_goals");
    }

    private static double getRecentXGC(int playerId) {
        return fetchRecentStat(playerId, "expected_goals_conceded");
    }

    private static double fetchRecentStat(int playerId, String statKey) {
        try {
            URL url = new URL("https://fantasy.premierleague.com/api/element-summary/"
                    + playerId + "/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) return 0;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray history = new JSONObject(sb.toString()).getJSONArray("history");
            double total = 0;
            int    start = Math.max(0, history.length() - RECENT_FIXTURES);

            for (int i = start; i < history.length(); i++) {
                try {
                    total += Double.parseDouble(
                            history.getJSONObject(i).getString(statKey));
                } catch (Exception ignored) {}
            }
            return total;

        } catch (Exception e) {
            return 0;
        }
    }

    // --- Public accessors ---

    public static double getTeamXGPerGame(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        if (fplId == null) { System.out.println("[FPL] No FPL ID for: " + fdTeamName); return -1; }
        double xg    = teamXG.getOrDefault(fplId, -1.0);
        int    played = Math.min(RECENT_FIXTURES, teamMatchesPlayed.getOrDefault(fplId, 1));
        if (xg < 0) return -1;
        return xg / played;
    }

    public static double getTeamXGConcededPerGame(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        if (fplId == null) return -1;
        return teamXGConceded.getOrDefault(fplId, -1.0);
    }

    public static int getStrengthAttackHome(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        return fplId != null ? strengthAttackHome.getOrDefault(fplId, 1200) : 1200;
    }

    public static int getStrengthAttackAway(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        return fplId != null ? strengthAttackAway.getOrDefault(fplId, 1200) : 1200;
    }

    public static int getStrengthDefenceHome(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        return fplId != null ? strengthDefenceHome.getOrDefault(fplId, 1200) : 1200;
    }

    public static int getStrengthDefenceAway(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        return fplId != null ? strengthDefenceAway.getOrDefault(fplId, 1200) : 1200;
    }

    public static double getTeamXG(String n)         { return getTeamXGPerGame(n); }

    /**
     * Feature 3: Returns an xG multiplier accounting for top scorer availability.
     * If the team's top scorer is injured/suspended AND they contribute
     * more than 30% of team xG, applies a proportional penalty.
     * Returns 1.0 (no penalty) if top scorer is available or data is missing.
     */
    public static double getKeyPlayerXGMultiplier(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        if (fplId == null) return 1.0;

        Boolean available = topScorerAvailable.get(fplId);
        if (available == null || available) return 1.0; // available — no penalty

        double topScorerXG = teamTopScorerXGShare.getOrDefault(fplId, 0.0);
        double totalXG     = teamXG.getOrDefault(fplId, 0.0);
        if (totalXG <= 0) return 1.0;

        double share = topScorerXG / totalXG;

        // Only penalise if top scorer contributes >30% of team xG
        if (share < 0.30) return 1.0;

        // Penalty scales with their share — max 20% reduction
        double penalty = Math.min(0.20, (share - 0.30) * 0.5);
        System.out.printf("[KEY-PLAYER] %s top scorer unavailable — xG share=%.1f%% → penalty=%.1f%%%n",
                fdTeamName, share * 100, penalty * 100);
        return 1.0 - penalty;
    }
    public static double getTeamXGConceded(String n) { return getTeamXGConcededPerGame(n); }
}