package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class FixtureFetcher {

    public static List<Match> getUpcomingMatches() {
        List<Match> matches = new ArrayList<>();

        JSONArray fixtures = APIFootballClient.getUpcomingFixtures();
        LocalDate cutoff = LocalDate.now().plusDays(7);

        for (int i = 0; i < fixtures.length(); i++) {
            try {
                JSONObject fixture = fixtures.getJSONObject(i);

                // Check date first — stop if beyond 7 days
                String dateStr = fixture.getString("utcDate").substring(0, 10);
                LocalDate matchDate = LocalDate.parse(dateStr);
                if (matchDate.isAfter(cutoff)) break;

                int fixtureId = fixture.getInt("id");

                JSONObject home = fixture.getJSONObject("homeTeam");
                JSONObject away = fixture.getJSONObject("awayTeam");

                int    homeId   = home.getInt("id");
                String homeName = home.getString("name");
                int    awayId   = away.getInt("id");
                String awayName = away.getString("name");

                matches.add(new Match(fixtureId, homeId, homeName, awayId, awayName));

            } catch (Exception e) {
                System.out.println("[WARN] Could not parse fixture " + i + ": " + e.getMessage());
            }
        }

        return matches;
    }
}