package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/**
 * Reconciles predictions with actual results.
 *
 * New in this version:
 * - Feature 5: per-team accuracy tracking in printSummary()
 * - Feature 9: blowout detection (margin ≥ 3, wrong prediction) flagged
 *              with 🌪️ in console and passed to TelegramNotifier.sendResult()
 * - Monthly summary trigger: checks if this is the first run of a new month
 *   and calls TelegramNotifier.sendMonthlyAccuracy() if so
 */
public class ResultTracker {

    private static final String FILE_PATH     = "data/predictions.csv";
    private static final String LAST_MONTH_FILE = "data/last_summary_month.txt";
    private static final int    BLOWOUT_MARGIN  = 3;

    private static final String HEADER =
            "date,season,home,away," +
                    "predHomeGoals,predAwayGoals," +
                    "homeWinProb,drawProb,awayWinProb," +
                    "predictedOutcome," +
                    "homeEloAtPrediction,awayEloAtPrediction," +
                    "actualHomeGoals,actualAwayGoals,correct\n";

    public static void reconcile() {
        System.out.println("[RESULTS] Checking for unrecorded results...");

        // Load referee stats so we can update them with new results
        RefereeAnalyzer.load();

        List<String[]> rows = loadCSV();
        if (rows.isEmpty()) {
            System.out.println("[RESULTS] No predictions on file yet.");
            checkMonthlyReport(rows);
            return;
        }

        JSONArray recentMatches = APIFootballClient.getRecentResults();
        if (recentMatches.isEmpty()) {
            System.out.println("[RESULTS] No recent results fetched.");
            checkMonthlyReport(rows);
            return;
        }

        int updated = 0;

        for (int i = 0; i < recentMatches.length(); i++) {
            JSONObject match = recentMatches.getJSONObject(i);

            String homeName = match.getJSONObject("homeTeam").getString("name");
            String awayName = match.getJSONObject("awayTeam").getString("name");

            JSONObject fullTime = match
                    .getJSONObject("score")
                    .getJSONObject("fullTime");

            int actualHome = fullTime.optInt("home", -1);
            int actualAway = fullTime.optInt("away", -1);
            if (actualHome < 0 || actualAway < 0) continue;

            for (String[] row : rows) {
                if (row.length < 15) continue;

                String predHome    = row[2].replace("\"", "");
                String predAway    = row[3].replace("\"", "");
                String alreadySet  = row[13].trim();

                boolean namesMatch   = predHome.equalsIgnoreCase(homeName)
                        && predAway.equalsIgnoreCase(awayName);
                boolean needsFilling = alreadySet.isEmpty();

                if (namesMatch && needsFilling) {
                    row[13] = String.valueOf(actualHome);
                    if (row.length > 14) row[14] = String.valueOf(actualAway);

                    String predictedOutcome = row[9].replace("\"", "").trim();
                    String actualOutcome    = getOutcome(homeName, awayName,
                            actualHome, actualAway);
                    boolean correct = predictedOutcome.equalsIgnoreCase(actualOutcome);
                    boolean blowout = !correct
                            && Math.abs(actualHome - actualAway) >= BLOWOUT_MARGIN;

                    // Update ELO
                    EloStore.recordResult(homeName, awayName, actualHome, actualAway);

                    // Update referee stats (feature 2)
                    String referee = RefereeAnalyzer.extractReferee(match);
                    if (referee != null) {
                        RefereeAnalyzer.recordMatch(referee, homeName, awayName,
                                actualHome, actualAway);
                    }

                    // Send Telegram result notification
                    try {
                        int predHomeGoals = Integer.parseInt(row[4].trim());
                        int predAwayGoals = Integer.parseInt(row[5].trim());
                        TelegramNotifier.sendResult(
                                homeName, awayName,
                                actualHome, actualAway,
                                predictedOutcome,
                                predHomeGoals, predAwayGoals);
                    } catch (Exception e) {
                        System.out.println("[RESULTS] Could not send Telegram result: "
                                + e.getMessage());
                    }

                    String blowoutTag = blowout ? " 🌪️ BLOWOUT" : "";
                    System.out.printf("[RESULTS] %s %d-%d %s (predicted: %s, %s%s)%n",
                            homeName, actualHome, actualAway, awayName,
                            predictedOutcome, correct ? "✅" : "❌", blowoutTag);

                    updated++;
                    break;
                }
            }
        }

        if (updated > 0) {
            rewriteCSV(rows);
            System.out.println("[RESULTS] Updated " + updated + " result(s)");
        } else {
            System.out.println("[RESULTS] No new results to record");
        }

        printSummary(rows);

        // Check if monthly report should be sent (feature 9)
        checkMonthlyReport(rows);
    }

    /**
     * Prints overall accuracy + per-team breakdown to console.
     * Feature 5: teams with accuracy below 40% are flagged.
     */
    public static void printSummary(List<String[]> rows) {
        int total = 0, correct = 0, blowouts = 0, pending = 0;

        // teamName -> [correct, total]
        Map<String, int[]> teamStats = new LinkedHashMap<>();

        for (String[] row : rows) {
            if (row.length < 3 || row[0].equals("date")) continue;
            String actualHomeStr = row.length > 13 ? row[13].trim() : "";
            String actualAwayStr = row.length > 14 ? row[14].trim() : "";

            if (actualHomeStr.isEmpty() || actualAwayStr.isEmpty()) {
                pending++;
                continue;
            }

            try {
                int ah = Integer.parseInt(actualHomeStr);
                int aa = Integer.parseInt(actualAwayStr);
                String home      = row[2].replace("\"", "").trim();
                String away      = row[3].replace("\"", "").trim();
                String predicted = row.length > 9
                        ? row[9].replace("\"", "").trim() : "";

                String actualOutcome = getOutcome(home, away, ah, aa);
                boolean isCorrect = predicted.equalsIgnoreCase(actualOutcome);
                boolean isBlowout = !isCorrect
                        && Math.abs(ah - aa) >= BLOWOUT_MARGIN;

                total++;
                if (isCorrect) correct++;
                if (isBlowout) blowouts++;

                // Per-team tracking
                for (String teamName : new String[]{home, away}) {
                    teamStats.computeIfAbsent(teamName, k -> new int[]{0, 0});
                    teamStats.get(teamName)[1]++;
                    if (isCorrect) teamStats.get(teamName)[0]++;
                }

            } catch (NumberFormatException ignored) {}
        }

        if (total > 0) {
            double pct = 100.0 * correct / total;
            System.out.printf("%n[ACCURACY] %d/%d correct (%.1f%%)  |  " +
                            "%d blowouts  |  %d pending%n",
                    correct, total, pct, blowouts, pending);

            // Print per-team breakdown — flag those below threshold
            System.out.println("[ACCURACY] Per-team breakdown:");
            teamStats.entrySet().stream()
                    .sorted((a, b) -> {
                        double pctA = 100.0 * a.getValue()[0]
                                / Math.max(a.getValue()[1], 1);
                        double pctB = 100.0 * b.getValue()[0]
                                / Math.max(b.getValue()[1], 1);
                        return Double.compare(pctB, pctA); // descending
                    })
                    .forEach(e -> {
                        double teamPct = 100.0 * e.getValue()[0]
                                / Math.max(e.getValue()[1], 1);
                        String flag = teamPct < 25.0  ? " 🚨"
                                : teamPct < 40.0 ? " ⚠️"
                                : "";
                        System.out.printf("  %-35s %d/%d  %.1f%%%s%n",
                                e.getKey(),
                                e.getValue()[0], e.getValue()[1],
                                teamPct, flag);
                    });
            System.out.println();
        } else {
            System.out.printf("[ACCURACY] No completed predictions  |  %d pending%n%n",
                    pending);
        }
    }

    /**
     * Checks if this is the first run of a new month.
     * If so, sends the monthly accuracy summary to Telegram.
     * Stores the last sent month in data/last_summary_month.txt
     */
    private static void checkMonthlyReport(List<String[]> rows) {
        try {
            String currentMonth = java.time.ZonedDateTime
                    .now(java.time.ZoneId.of("Africa/Johannesburg"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

            // Read last sent month
            String lastMonth = "";
            File lastMonthFile = new File(LAST_MONTH_FILE);
            if (lastMonthFile.exists()) {
                try (BufferedReader r = new BufferedReader(
                        new FileReader(lastMonthFile))) {
                    lastMonth = r.readLine().trim();
                }
            }

            if (!currentMonth.equals(lastMonth)) {
                System.out.println("[MONTHLY] New month detected — sending accuracy report");
                TelegramNotifier.sendMonthlyAccuracy(rows);

                // Save current month so we don't send again this month
                new File("data").mkdirs();
                try (FileWriter fw = new FileWriter(LAST_MONTH_FILE)) {
                    fw.write(currentMonth);
                }
            }
        } catch (Exception e) {
            System.out.println("[MONTHLY] Could not check monthly report: "
                    + e.getMessage());
        }
    }

    private static String getOutcome(String home, String away,
                                     int homeGoals, int awayGoals) {
        if (homeGoals > awayGoals) return home + " Win";
        if (awayGoals > homeGoals) return away + " Win";
        return "Draw";
    }

    public static List<String[]> loadCSV() {
        List<String[]> rows = new ArrayList<>();
        File file = new File(FILE_PATH);
        if (!file.exists()) return rows;

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; }
                if (!line.trim().isEmpty()) rows.add(parseCSVLine(line));
            }
        } catch (Exception e) {
            System.out.println("[RESULTS] Error reading CSV: " + e.getMessage());
        }
        return rows;
    }

    private static void rewriteCSV(List<String[]> rows) {
        try (FileWriter writer = new FileWriter(FILE_PATH)) {
            writer.write(HEADER);
            for (String[] row : rows) {
                String correct = "";
                if (row.length > 14 && !row[13].trim().isEmpty()) {
                    try {
                        int ah = Integer.parseInt(row[13].trim());
                        int aa = Integer.parseInt(row[14].trim());
                        String predicted = row[9].replace("\"", "").trim();
                        String actual    = getOutcome(
                                row[2].replace("\"", ""),
                                row[3].replace("\"", ""),
                                ah, aa);
                        correct = predicted.equalsIgnoreCase(actual) ? "TRUE" : "FALSE";
                    } catch (Exception ignored) {}
                }

                writer.write(String.join(",",
                        safe(row,0), safe(row,1), safe(row,2), safe(row,3),
                        safe(row,4), safe(row,5), safe(row,6), safe(row,7),
                        safe(row,8), safe(row,9), safe(row,10), safe(row,11),
                        safe(row,12), safe(row,13), correct) + "\n");
            }
        } catch (Exception e) {
            System.out.println("[RESULTS] Error rewriting CSV: " + e.getMessage());
        }
    }

    private static String safe(String[] row, int i) {
        return i < row.length && row[i] != null ? row[i].trim() : "";
    }

    private static String[] parseCSVLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else current.append(c);
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}