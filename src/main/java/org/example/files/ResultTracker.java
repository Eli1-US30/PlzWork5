package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/**
 * Reconciles predictions with actual results.
 * Now sends a Telegram message for each newly recorded result
 * showing predicted vs actual with correct/incorrect flag.
 */
public class ResultTracker {

    private static final String FILE_PATH = "data/predictions.csv";

    private static final String HEADER =
            "date,season,home,away," +
                    "predHomeGoals,predAwayGoals," +
                    "homeWinProb,drawProb,awayWinProb," +
                    "predictedOutcome," +
                    "homeEloAtPrediction,awayEloAtPrediction," +
                    "actualHomeGoals,actualAwayGoals,correct\n";

    public static void reconcile() {
        System.out.println("[RESULTS] Checking for unrecorded results...");

        List<String[]> rows = loadCSV();
        if (rows.isEmpty()) {
            System.out.println("[RESULTS] No predictions on file yet.");
            return;
        }

        JSONArray recentMatches = APIFootballClient.getRecentResults();
        if (recentMatches.isEmpty()) {
            System.out.println("[RESULTS] No recent results fetched.");
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

                String predHome   = row[2].replace("\"", "");
                String predAway   = row[3].replace("\"", "");
                String alreadySet = row[13].trim();

                boolean namesMatch  = predHome.equalsIgnoreCase(homeName)
                        && predAway.equalsIgnoreCase(awayName);
                boolean needsFilling = alreadySet.isEmpty();

                if (namesMatch && needsFilling) {
                    row[13] = String.valueOf(actualHome);
                    if (row.length > 14) row[14] = String.valueOf(actualAway);

                    // Determine if prediction was correct
                    String predictedOutcome = row[9].replace("\"", "").trim();
                    String actualOutcome    = getOutcome(homeName, awayName,
                            actualHome, actualAway);
                    boolean correct = predictedOutcome.equalsIgnoreCase(actualOutcome);

                    // Update ELO
                    EloStore.recordResult(homeName, awayName, actualHome, actualAway);

                    // Send Telegram result notification
                    try {
                        int predHome_goals = Integer.parseInt(row[4].trim());
                        int predAway_goals = Integer.parseInt(row[5].trim());
                        TelegramNotifier.sendResult(
                                homeName, awayName,
                                actualHome, actualAway,
                                predictedOutcome,
                                predHome_goals, predAway_goals);
                    } catch (Exception e) {
                        System.out.println("[RESULTS] Could not send Telegram result: "
                                + e.getMessage());
                    }

                    System.out.printf("[RESULTS] %s %d-%d %s (predicted: %s, %s)%n",
                            homeName, actualHome, actualAway, awayName,
                            predictedOutcome, correct ? "✅" : "❌");

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
    }

    public static void printSummary(List<String[]> rows) {
        int total = 0, correct = 0, pending = 0;

        for (String[] row : rows) {
            if (row.length < 3 || row[0].equals("date")) continue;
            String actualHome = row.length > 13 ? row[13].trim() : "";
            String actualAway = row.length > 14 ? row[14].trim() : "";

            if (actualHome.isEmpty() || actualAway.isEmpty()) { pending++; continue; }
            total++;

            String predictedOutcome = row.length > 9
                    ? row[9].replace("\"", "").trim() : "";
            String home = row[2].replace("\"", "");
            String away = row[3].replace("\"", "");

            try {
                int ah = Integer.parseInt(actualHome);
                int aa = Integer.parseInt(actualAway);
                if (predictedOutcome.equalsIgnoreCase(getOutcome(home, away, ah, aa)))
                    correct++;
            } catch (NumberFormatException ignored) {}
        }

        if (total > 0) {
            System.out.printf("%n[ACCURACY] %d/%d correct (%.1f%%)  |  %d pending%n%n",
                    correct, total, 100.0 * correct / total, pending);
        } else {
            System.out.printf("[ACCURACY] No completed predictions  |  %d pending%n%n",
                    pending);
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