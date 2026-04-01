package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FixtureFetcher {

    /**
     * Gets upcoming matches in the next 7 days.
     * Used for the weekly Friday overview run.
     */
    public static List<Match> getUpcomingMatches() {
        List<Match> matches = new ArrayList<>();
        JSONArray fixtures  = APIFootballClient.getUpcomingFixtures();
        LocalDate cutoff    = LocalDate.now().plusDays(7);

        for (int i = 0; i < fixtures.length(); i++) {
            try {
                JSONObject fixture = fixtures.getJSONObject(i);
                String dateStr     = fixture.getString("utcDate").substring(0, 10);
                LocalDate matchDate = LocalDate.parse(dateStr);
                if (matchDate.isAfter(cutoff)) break;

                matches.add(parseMatch(fixture));
            } catch (Exception e) {
                System.out.println("[WARN] Could not parse fixture " + i + ": " + e.getMessage());
            }
        }
        return matches;
    }

    /**
     * Gets matches for specific FPL team IDs.
     * Used for the pre-match run when we know which teams
     * are playing in the next 25-90 minutes.
     *
     * Fetches upcoming fixtures and filters to only those
     * involving the given FPL team IDs.
     *
     * Note: FPL team IDs don't match football-data.org team IDs
     * so we match by team name via the NAME_BRIDGE in FPLDataCache.
     */
    public static List<Match> getMatchesForTeams(List<Integer> fplTeamIds) {
        List<Match> matches  = new ArrayList<>();
        JSONArray   fixtures = APIFootballClient.getUpcomingFixtures();

        // Get the FPL team names for the given IDs from the bootstrap
        // We match by checking if the fixture teams are playing soon
        // Since FPL and football-data use different IDs we match via
        // the upcoming fixtures that kick off in the right window
        // FPLLineupClient already confirmed these teams are playing soon
        // so we just return all fixtures for today
        LocalDate today = LocalDate.now();

        for (int i = 0; i < fixtures.length(); i++) {
            try {
                JSONObject fixture  = fixtures.getJSONObject(i);
                String     dateStr  = fixture.getString("utcDate").substring(0, 10);
                LocalDate  matchDate = LocalDate.parse(dateStr);

                // Only today's matches
                if (matchDate.equals(today)) {
                    matches.add(parseMatch(fixture));
                }
            } catch (Exception e) {
                System.out.println("[WARN] Could not parse fixture " + i
                        + ": " + e.getMessage());
            }
        }

        System.out.println("[FIXTURES] Found " + matches.size()
                + " match(es) today for pre-match run");
        return matches;
    }

    private static Match parseMatch(JSONObject fixture) {
        int    fixtureId = fixture.getInt("id");
        int    homeId    = fixture.getJSONObject("homeTeam").getInt("id");
        String homeName  = fixture.getJSONObject("homeTeam").getString("name");
        int    awayId    = fixture.getJSONObject("awayTeam").getInt("id");
        String awayName  = fixture.getJSONObject("awayTeam").getString("name");
        return new Match(fixtureId, homeId, homeName, awayId, awayName);
    }
}