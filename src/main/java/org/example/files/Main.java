package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Main entry point for Fly.io deployment.
 *
 * Runs as a continuous loop — never exits.
 * Each day:
 *   1. Wakes up at 8am SA time
 *   2. Fetches today's PL kickoff times from FPL
 *   3. No games → sends "no games" notification → sleeps until tomorrow 8am
 *   4. Games today → sends "games today" notification
 *   5. For each match: sleeps until 75 min before kickoff
 *                      grabs lineups → full prediction → sends Telegram
 *   6. After last match → sleeps until tomorrow 8am
 *
 * GitHub Actions (predictor.yml) kept as backup — runs weekly on Fridays.
 */
public class Main {

    private static final ZoneId SA_ZONE           = ZoneId.of("Africa/Johannesburg");
    private static final int    WAKE_HOUR          = 8;   // 8am SA time daily wakeup
    private static final int    PRE_MATCH_MINUTES  = 75;  // predict 75 min before kickoff

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("      SOCCER PREDICTOR v3.0 — Fly.io");
        System.out.println("==============================================");
        System.out.println("Running as continuous loop — never exits");
        System.out.println("Wakes up at " + WAKE_HOUR + "am SA time each day");
        System.out.println("==============================================\n");

        // Load persistent data once at startup
        EloStore.load();
        RefereeAnalyzer.load();

        // Main loop — runs forever
        while (true) {
            try {
                runDailyCheck();
            } catch (Exception e) {
                System.out.println("[MAIN] Unexpected error: " + e.getMessage());
                e.printStackTrace();
            }
            sleepUntilTomorrow();
        }
    }

    // --------------------------------------------------------
    // Daily check — runs once per day at 8am SA time
    // --------------------------------------------------------
    private static void runDailyCheck() {
        ZonedDateTime now = ZonedDateTime.now(SA_ZONE);
        System.out.println("\n[DAILY] Starting daily check — "
                + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

        // Reconcile any results from yesterday
        PredictionLogger.init();
        ResultTracker.reconcile();

        // Check FPL for today's games
        boolean gamesToday = FPLLineupClient.hasGamesToday();

        if (!gamesToday) {
            System.out.println("[DAILY] No games today");
            TelegramNotifier.sendNoGamesToday();
            return;
        }

        // Build name map
        Map<Integer, String> fplIdToName = buildLightFplNameMap();

        // Send games today notification
        List<TelegramNotifier.UpcomingGame> todaysGames =
                FPLLineupClient.getTodaysGames(fplIdToName);
        if (!todaysGames.isEmpty()) {
            TelegramNotifier.sendGamesToday(todaysGames);
            System.out.println("[DAILY] Sent games today — "
                    + todaysGames.size() + " match(es)");
        }

        // Parse kickoff times
        JSONArray allFixtures = FPLLineupClient.getAllFixtures();
        List<ScheduledMatch> todayMatches =
                getTodayMatchesWithKickoffs(allFixtures, fplIdToName);

        if (todayMatches.isEmpty()) {
            System.out.println("[DAILY] Could not parse kickoff times");
            return;
        }

        // Sort by kickoff
        todayMatches.sort(Comparator.comparing(m -> m.kickoffUTC));

        System.out.println("[DAILY] " + todayMatches.size() + " match(es) scheduled:");
        for (ScheduledMatch m : todayMatches) {
            ZonedDateTime kickoffSA = m.kickoffUTC.withZoneSameInstant(SA_ZONE);
            ZonedDateTime predictAt = kickoffSA.minusMinutes(PRE_MATCH_MINUTES);
            System.out.printf("  %s vs %s  kickoff=%s  predict=%s%n",
                    m.homeTeam, m.awayTeam,
                    kickoffSA.format(DateTimeFormatter.ofPattern("HH:mm")),
                    predictAt.format(DateTimeFormatter.ofPattern("HH:mm")));
        }

        // Process each match in order
        for (ScheduledMatch match : todayMatches) {
            processMatch(match);
        }

        System.out.println("[DAILY] All matches processed");
    }

    // --------------------------------------------------------
    // Sleep until prediction time then predict
    // --------------------------------------------------------
    private static void processMatch(ScheduledMatch match) {
        ZonedDateTime now       = ZonedDateTime.now(SA_ZONE);
        ZonedDateTime kickoffSA = match.kickoffUTC.withZoneSameInstant(SA_ZONE);
        ZonedDateTime predictAt = kickoffSA.minusMinutes(PRE_MATCH_MINUTES);

        System.out.printf("%n[MATCH] %s vs %s — kickoff %s SA%n",
                match.homeTeam, match.awayTeam,
                kickoffSA.format(DateTimeFormatter.ofPattern("HH:mm")));

        if (now.isAfter(kickoffSA)) {
            System.out.println("[MATCH] Kickoff already passed — skipping");
            return;
        }

        if (now.isBefore(predictAt)) {
            long sleepMins = Duration.between(now, predictAt).toMinutes();
            System.out.printf("[MATCH] Sleeping %d min until %s SA%n",
                    sleepMins,
                    predictAt.format(DateTimeFormatter.ofPattern("HH:mm")));
            sleepMinutes(sleepMins);
        }

        System.out.println("[MATCH] Prediction window — running prediction");
        runPrediction(match);
    }

    // --------------------------------------------------------
    // Full prediction for one match
    // --------------------------------------------------------
    private static void runPrediction(ScheduledMatch scheduledMatch) {
        try {
            // Load lineups now that we're in the window
            JSONBootstrapHelper.loadCurrentGameweek();

            // Load all data sources
            FPLDataCache.initialize();
            TheSportsDBClient.preload();
            StandingsClient.preload();
            OddsClient.preload();

            // Get match from football-data.org
            List<Integer> teamIds = new ArrayList<>();
            teamIds.add(scheduledMatch.fplHomeId);
            teamIds.add(scheduledMatch.fplAwayId);
            List<Match> matches = FixtureFetcher.getMatchesForTeams(teamIds);

            if (matches.isEmpty()) {
                System.out.println("[PREDICT] Match not found in football-data.org");
                return;
            }

            List<Prediction> finalPredictions = new ArrayList<>();
            List<OddsBlender.BlendedProbabilities> blendedList = new ArrayList<>();

            for (Match match : matches) {
                System.out.println("==============================================");
                System.out.println("  " + match.getHomeTeamName()
                        + " vs " + match.getAwayTeamName());
                System.out.println("==============================================");

                Team home = StatsCalculator.buildTeam(
                        match.getHomeTeamId(), match.getHomeTeamName());
                Team away = StatsCalculator.buildTeam(
                        match.getAwayTeamId(), match.getAwayTeamName());

                Simulator.SimulationResult sim = Simulator.simulate(home, away);

                OddsBlender.BlendedProbabilities blended = OddsBlender.blend(
                        match.getHomeTeamName(), match.getAwayTeamName(),
                        sim.homeWinProb, sim.drawProb, sim.awayWinProb,
                        sim.over15Prob, sim.over25Prob, sim.over35Prob);

                Prediction finalPrediction = new Prediction(
                        home.getName(), away.getName(),
                        sim.prediction.getHomeGoals(),
                        sim.prediction.getAwayGoals(),
                        blended.homeWinProb,
                        blended.awayWinProb,
                        blended.drawProb);

                PredictionLogger.log(finalPrediction, home, away);
                finalPredictions.add(finalPrediction);
                blendedList.add(blended);

                System.out.printf("  Over 1.5: %.1f%%  2.5: %.1f%%  3.5: %.1f%%%n",
                        blended.over15Prob, blended.over25Prob, blended.over35Prob);
            }

            if (!finalPredictions.isEmpty()) {
                TelegramNotifier.sendPredictions(matches, finalPredictions, blendedList);
                System.out.println("[PREDICT] Sent to Telegram");
            }

            // Reset FPL cache so next match gets fresh lineup data
            FPLDataCache.reset();

        } catch (Exception e) {
            System.out.println("[PREDICT] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --------------------------------------------------------
    // Sleep until tomorrow 8am SA time
    // --------------------------------------------------------
    private static void sleepUntilTomorrow() {
        ZonedDateTime now      = ZonedDateTime.now(SA_ZONE);
        ZonedDateTime tomorrow = now.toLocalDate()
                .plusDays(1)
                .atTime(WAKE_HOUR, 0)
                .atZone(SA_ZONE);

        long sleepMins = Duration.between(now, tomorrow).toMinutes();
        System.out.printf("%n[SLEEP] Sleeping %d min until tomorrow %dam SA (%s)%n",
                sleepMins, WAKE_HOUR,
                tomorrow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        sleepMinutes(sleepMins);
    }

    private static void sleepMinutes(long minutes) {
        try {
            if (minutes > 0) Thread.sleep(minutes * 60 * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------------
    // Parse today's fixtures with kickoff times from FPL
    // --------------------------------------------------------
    private static List<ScheduledMatch> getTodayMatchesWithKickoffs(
            JSONArray fixtures, Map<Integer, String> fplIdToName) {

        List<ScheduledMatch> matches = new ArrayList<>();
        LocalDate today = LocalDate.now(SA_ZONE);

        for (int i = 0; i < fixtures.length(); i++) {
            try {
                JSONObject fixture = fixtures.getJSONObject(i);
                String kickoffStr  = fixture.optString("kickoff_time", null);
                if (kickoffStr == null) continue;
                if (fixture.optBoolean("finished", false)) continue;

                ZonedDateTime kickoffUTC  = ZonedDateTime.parse(kickoffStr,
                        DateTimeFormatter.ISO_DATE_TIME);
                LocalDate kickoffDate = kickoffUTC
                        .withZoneSameInstant(SA_ZONE).toLocalDate();
                if (!kickoffDate.equals(today)) continue;

                int homeId = fixture.optInt("team_h", -1);
                int awayId = fixture.optInt("team_a", -1);
                if (homeId < 0 || awayId < 0) continue;

                String homeName = fplIdToName.getOrDefault(homeId, "Team " + homeId);
                String awayName = fplIdToName.getOrDefault(awayId, "Team " + awayId);

                matches.add(new ScheduledMatch(
                        homeId, awayId, homeName, awayName, kickoffUTC));

            } catch (Exception e) {
                System.out.println("[DAILY] Fixture parse error: " + e.getMessage());
            }
        }
        return matches;
    }

    // --------------------------------------------------------
    // Lightweight FPL ID -> name map
    // --------------------------------------------------------
    private static Map<Integer, String> buildLightFplNameMap() {
        Map<Integer, String> map = new HashMap<>();
        try {
            JSONObject bootstrap = FPLClient.getBootstrapStatic();
            if (bootstrap == null) return map;
            JSONArray teams = bootstrap.getJSONArray("teams");
            for (int i = 0; i < teams.length(); i++) {
                JSONObject team = teams.getJSONObject(i);
                map.put(team.getInt("id"), team.getString("name"));
            }
        } catch (Exception e) {
            System.out.println("[INIT] FPL name map error: " + e.getMessage());
        }
        return map;
    }

    // --------------------------------------------------------
    // Data class for a scheduled match
    // --------------------------------------------------------
    private static class ScheduledMatch {
        final int           fplHomeId;
        final int           fplAwayId;
        final String        homeTeam;
        final String        awayTeam;
        final ZonedDateTime kickoffUTC;

        ScheduledMatch(int fplHomeId, int fplAwayId,
                       String homeTeam, String awayTeam,
                       ZonedDateTime kickoffUTC) {
            this.fplHomeId  = fplHomeId;
            this.fplAwayId  = fplAwayId;
            this.homeTeam   = homeTeam;
            this.awayTeam   = awayTeam;
            this.kickoffUTC = kickoffUTC;
        }
    }
}