package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class FPLDataCache {

    private static final Map<Integer, Integer> teamMatchesPlayed = new HashMap<>();
    private static final Map<Integer, String>  fplTeamNames      = new HashMap<>();
    private static final Map<String, Integer>  nameToFplId       = new HashMap<>();
    private static final Map<Integer, Double>  teamXG            = new HashMap<>();

    // Now stores the starting GK's xGC per game rather than a team sum
    private static final Map<Integer, Double>  teamXGConceded    = new HashMap<>();

    private static boolean initialized = false;

    private static final Map<String, String> NAME_BRIDGE = new HashMap<>();
    static {
        NAME_BRIDGE.put("Manchester City FC",           "Man City");
        NAME_BRIDGE.put("Arsenal FC",                   "Arsenal");
        NAME_BRIDGE.put("Liverpool FC",                 "Liverpool");
        NAME_BRIDGE.put("Chelsea FC",                   "Chelsea");
        NAME_BRIDGE.put("Manchester United FC",         "Man Utd");
        NAME_BRIDGE.put("Tottenham Hotspur FC",         "Spurs");
        NAME_BRIDGE.put("Newcastle United FC",          "Newcastle");
        NAME_BRIDGE.put("Aston Villa FC",               "Aston Villa");
        NAME_BRIDGE.put("Brighton & Hove Albion FC",    "Brighton");
        NAME_BRIDGE.put("West Ham United FC",           "West Ham");
        NAME_BRIDGE.put("Brentford FC",                 "Brentford");
        NAME_BRIDGE.put("Fulham FC",                    "Fulham");
        NAME_BRIDGE.put("Crystal Palace FC",            "Crystal Palace");
        NAME_BRIDGE.put("Wolverhampton Wanderers FC",   "Wolves");
        NAME_BRIDGE.put("Everton FC",                   "Everton");
        NAME_BRIDGE.put("Nottingham Forest FC",         "Nott'm Forest");
        NAME_BRIDGE.put("AFC Bournemouth",              "Bournemouth");
        NAME_BRIDGE.put("Leicester City FC",            "Leicester");
        NAME_BRIDGE.put("Ipswich Town FC",              "Ipswich");
        NAME_BRIDGE.put("Southampton FC",               "Southampton");
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

        // Step 2: build FPL team ID -> name map
        JSONArray teams = bootstrap.getJSONArray("teams");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.getJSONObject(i);
            int id   = team.getInt("id");
            String name = team.getString("name");
            fplTeamNames.put(id, name);
            teamMatchesPlayed.put(id, Math.max(currentGameweek - 1, 1));
        }

        // Step 3: build football-data.org name -> FPL ID reverse map
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

        // Step 4: sum outfield xG (all available non-GK players)
        // Step 5: find the most likely starting GK per team for xGC
        //
        // For GK xGC we no longer sum all available keepers.
        // Instead we find the one with the most minutes among available GKs —
        // that's almost certainly the current starter. If two keepers are close
        // in minutes (within 10%) we blend them weighted by minutes.
        //
        // Interim storage: teamId -> list of {xgc_per_game, minutes, availability}
        Map<Integer, Double> gkBestMinutes  = new HashMap<>(); // best GK minutes so far
        Map<Integer, Double> gkBestXGCRate  = new HashMap<>(); // that GK's xGC per game
        Map<Integer, Double> gkSecondMinutes = new HashMap<>();
        Map<Integer, Double> gkSecondXGCRate = new HashMap<>();

        JSONArray players = bootstrap.getJSONArray("elements");
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.getJSONObject(i);

            int    teamId         = player.getInt("team");
            String status         = player.getString("status");
            int    chanceOfPlaying = player.optInt("chance_of_playing_next_round", 100);
            int    elementType    = player.getInt("element_type"); // 1=GK

            // Skip fully unavailable players
            if (status.equals("i") || status.equals("s") || chanceOfPlaying == 0) continue;

            double availability = chanceOfPlaying / 100.0;
            int    minutes      = player.optInt("minutes", 0);

            double xg  = 0;
            double xgc = 0;
            try {
                xg  = Double.parseDouble(player.getString("expected_goals"));
                xgc = Double.parseDouble(player.getString("expected_goals_conceded"));
            } catch (Exception e) {
                // no xG data for this player
            }

            int played = teamMatchesPlayed.getOrDefault(teamId, 1);

            if (elementType == 1) {
                // --- Goalkeeper logic ---
                // effective minutes = actual minutes * availability probability
                double effectiveMinutes = minutes * availability;
                double xgcPerGame = played > 0 ? xgc / played : 0;

                double bestMins = gkBestMinutes.getOrDefault(teamId, -1.0);

                if (effectiveMinutes > bestMins) {
                    // This GK has more minutes — demote old best to second
                    if (bestMins >= 0) {
                        gkSecondMinutes.put(teamId, bestMins);
                        gkSecondXGCRate.put(teamId, gkBestXGCRate.getOrDefault(teamId, 0.0));
                    }
                    gkBestMinutes.put(teamId, effectiveMinutes);
                    gkBestXGCRate.put(teamId, xgcPerGame);
                } else if (effectiveMinutes > gkSecondMinutes.getOrDefault(teamId, -1.0)) {
                    gkSecondMinutes.put(teamId, effectiveMinutes);
                    gkSecondXGCRate.put(teamId, xgcPerGame);
                }

            } else {
                // --- Outfield player: accumulate xG ---
                teamXG.merge(teamId, xg * availability, Double::sum);
            }
        }

        // Step 6: compute final GK xGC per team
        // If the backup has played within 20% of the starter's minutes, blend them
        // (catches mid-season GK switches like an injury 10 games ago)
        for (Integer teamId : fplTeamNames.keySet()) {
            double bestMins   = gkBestMinutes.getOrDefault(teamId, 0.0);
            double bestRate   = gkBestXGCRate.getOrDefault(teamId, -1.0);
            double secondMins = gkSecondMinutes.getOrDefault(teamId, 0.0);
            double secondRate = gkSecondXGCRate.getOrDefault(teamId, -1.0);

            if (bestRate < 0) {
                // No available GK found — skip
                continue;
            }

            double finalXGC;
            String gkNote;

            if (secondRate >= 0 && bestMins > 0 && (secondMins / bestMins) >= 0.8) {
                // Two keepers with similar minutes — genuine competition or recent switch
                // Blend proportionally by minutes
                double total = bestMins + secondMins;
                finalXGC = (bestRate * bestMins + secondRate * secondMins) / total;
                gkNote = String.format("blended (%.0f/%.0f mins) → xGC/g=%.2f",
                        bestMins, secondMins, finalXGC);
            } else {
                // Clear starter
                finalXGC = bestRate;
                gkNote = String.format("starter (%.0f mins) → xGC/g=%.2f", bestMins, finalXGC);
            }

            teamXGConceded.put(teamId, finalXGC);

            String teamName = fplTeamNames.getOrDefault(teamId, "ID=" + teamId);
            System.out.printf("[FPL-GK] %-20s %s%n", teamName, gkNote);
        }

        System.out.println("[FPL] Cache built for " + fplTeamNames.size() + " teams");
        initialized = true;
    }

    // --- Public accessors (unchanged interface) ---

    public static double getTeamXGPerGame(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        if (fplId == null) {
            System.out.println("[FPL] No FPL ID found for: " + fdTeamName);
            return -1;
        }
        double xg     = teamXG.getOrDefault(fplId, -1.0);
        int    played = teamMatchesPlayed.getOrDefault(fplId, 1);
        if (xg < 0) return -1;
        return xg / played;
    }

    public static double getTeamXGConcededPerGame(String fdTeamName) {
        Integer fplId = nameToFplId.get(fdTeamName);
        if (fplId == null) return -1;
        // Already stored as per-game rate now
        return teamXGConceded.getOrDefault(fplId, -1.0);
    }

    // Legacy methods kept so nothing else breaks
    public static double getTeamXG(String fdTeamName) {
        return getTeamXGPerGame(fdTeamName);
    }

    public static double getTeamXGConceded(String fdTeamName) {
        return getTeamXGConcededPerGame(fdTeamName);
    }
}