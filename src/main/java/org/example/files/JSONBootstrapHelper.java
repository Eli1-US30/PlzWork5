package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lightweight helper to get the current gameweek number
 * without initialising the full FPLDataCache.
 * Used by Main to load lineups before the cache builds.
 */
public class JSONBootstrapHelper {

    private static int currentGameweek = -1;

    public static void loadCurrentGameweek() {
        if (currentGameweek > 0) return;

        try {
            JSONObject bootstrap = FPLClient.getBootstrapStatic();
            if (bootstrap == null) { currentGameweek = 28; return; }

            JSONArray events = bootstrap.getJSONArray("events");
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.optBoolean("is_current", false)) {
                    currentGameweek = event.getInt("id");
                    System.out.println("[GW] Current gameweek: " + currentGameweek);
                    FPLLineupClient.loadLineups(currentGameweek);
                    return;
                }
            }
            currentGameweek = 28;
        } catch (Exception e) {
            System.out.println("[GW] Could not determine gameweek: " + e.getMessage());
            currentGameweek = 28;
        }
    }

    public static int getCurrentGameweek() {
        return currentGameweek > 0 ? currentGameweek : 28;
    }
}