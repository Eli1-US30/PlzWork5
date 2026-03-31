package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Fetches recent results from football-data.org, matches them against
 * stored predictions, fills in the actual score columns in predictions.csv,
 * updates ELO ratings, and writes a running accuracy summary.
 *
 * Run this AFTER matches have been played — ideally at the start of each
 * new run so last week's results get recorded before this week's predictions
 * are made.
 *
 * Flow:
 *   1. Fetch last 10 finished PL matches from football-data.org
 *   2. For each finished match, look for a matching row in predictions.csv
 *      (matched by home team + away team, result not yet filled in)
 *   3. Fill in actualHomeGoals, actualAwayGoals, correct
 *   4. Update ELO via EloStore
 *   5. Rewrite the CSV with filled-in results
 *   6. Print accuracy summary to console
 */
public class ResultTracker {

    private static final String FILE_PATH = "data/predictions.csv";

    /**
     * Call at startup before predictions are made.
     * Fetches recent results and reconciles against stored predictions.
     */
    public static void reconcile() {
        System.out.println("[RESULTS] Checking for unrecorded results...");

        // Load all rows from CSV
        List<String[]> rows = loadCSV();
        if (rows.isEmpty()) {
            System.out.println("[RESULTS] No predictions on file yet.");
            return;
        }

        // Fetch recent finished matches from API
        JSONArray recentMatches = APIFootballClient.getRecentResults();
        if (recentMatches.isEmpty()) {
            System.out.println("[RESULTS] No recent results fetched.");
            return;
        }

        int updated = 0;

        // For each finished match, find matching prediction row
        for (int i = 0; i < recentMatches.length(); i++) {
            JSONObject match = recentMatches.getJSONObject(i);

            String homeName = match.getJSONObject("homeTeam").getString("name");
            String awayName = match.getJSONObject("awayTeam").getString("name");

            JSONObject fullTime = match
                    .getJSONObject("score")
                    .getJSONObject("fullTime");

            int actualHome = fullTime.optInt("home", -1);
            int actualAway = fullTime.optInt("away", -1);

            if (actualHome < 0 || actualAway < 0) continue; // score not available

            // Find matching row in CSV that has no result yet
            for (String[] row : rows) {
                if (row.length < 15) continue;

                String predHome   = row[2].replace("\"", "");
                String predAway   = row[3].replace("\"", "");
                String alreadySet = row[13].trim(); // actualHomeGoals column

                boolean namesMatch = predHome.equalsIgnoreCase(homeName)
                        && predAway.equalsIgnoreCase(awayName);
                boolean needsFilling = alreadySet.isEmpty();

                if (namesMatch && needsFilling) {
                    // Fill in result
                    row[13] = String.valueOf(actualHome);
                    row[14] = String.valueOf(actualAway);

                    // Was the predicted outcome correct?
                    String predictedOutcome = row[9].replace("\"", "");
                    String actualOutcome    = getOutcome(homeName, awayName, actualHome, actualAway);
                    boolean correct         = predictedOutcome.equalsIgnoreCase(actualOutcome);
                    row[14] = String.valueOf(actualAway); // ensure set
                    // row has 15 cols: indices 0-14, correct is index 14 but
                    // we need to handle array size — extend if needed
                    if (row.length > 14) row[14] = String.valueOf(actualAway);

                    // Rebuild row with correct flag at the end
                    // CSV columns: ...,actualHomeGoals,actualAwayGoals,correct
                    row[13] = String.valueOf(actualHome);
                    if (row.length > 14) row[14] = String.valueOf(actualAway);

                    // We store correct as a separate operation on the row array
                    // Since arrays are fixed size we track it via a parallel approach
                    // The rewrite below handles it cleanly

                    // Update ELO
                    EloStore.recordResult(homeName, awayName, actualHome, actualAway);

                    System.out.printf("[RESULTS] Recorded: %s %d-%d %s (predicted: %s, correct: %s)%n",
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

    /**
     * Prints accuracy stats to console.
     */
    public static void printSummary(List<String[]> rows) {
        int total   = 0;
        int correct = 0;
        int pending = 0;

        for (String[] row : rows) {
            if (row.length < 3 || row[0].equals("date")) continue; // skip header

            String actualHome = row.length > 13 ? row[13].trim() : "";
            String actualAway = row.length > 14 ? row[14].trim() : "";

            if (actualHome.isEmpty() || actualAway.isEmpty()) {
                pending++;
                continue;
            }

            total++;

            // Re-derive whether correct from stored data
            String predictedOutcome = row.length > 9 ? row[9].replace("\"", "").trim() : "";
            String home = row[2].replace("\"", "");
            String away = row[3].replace("\"", "");

            try {
                int ah = Integer.parseInt(actualHome);
                int aa = Integer.parseInt(actualAway);
                String actualOutcome = getOutcome(home, away, ah, aa);
                if (predictedOutcome.equalsIgnoreCase(actualOutcome)) correct++;
            } catch (NumberFormatException ignored) {}
        }

        if (total > 0) {
            System.out.printf("%n[ACCURACY] %d/%d correct (%.1f%%)  |  %d pending results%n%n",
                    correct, total, 100.0 * correct / total, pending);
        } else {
            System.out.printf("[ACCURACY] No completed predictions yet  |  %d pending%n%n", pending);
        }
    }

    /**
     * Converts a scoreline into an outcome string matching Prediction.getOutcome() format.
     */
    private static String getOutcome(String home, String away, int homeGoals, int awayGoals) {
        if (homeGoals > awayGoals) return home + " Win";
        if (awayGoals > homeGoals) return away + " Win";
        return "Draw";
    }

    /**
     * Loads all rows from the CSV into a list of String arrays.
     * Skips the header row.
     */
    public static List<String[]> loadCSV() {
        List<String[]> rows = new ArrayList<>();
        File file = new File(FILE_PATH);
        if (!file.exists()) return rows;

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // skip header
                }
                if (!line.trim().isEmpty()) {
                    rows.add(parseCSVLine(line));
                }
            }
        } catch (Exception e) {
            System.out.println("[RESULTS] Error reading CSV: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Rewrites the entire CSV with updated rows.
     */
    private static void rewriteCSV(List<String[]> rows) {
        String header = "date,season,home,away," +
                "predHomeGoals,predAwayGoals," +
                "homeWinProb,drawProb,awayWinProb," +
                "predictedOutcome," +
                "homeEloAtPrediction,awayEloAtPrediction," +
                "actualHomeGoals,actualAwayGoals,correct\n";

        try (FileWriter writer = new FileWriter(FILE_PATH)) {
            writer.write(header);
            for (String[] row : rows) {
                // Recalculate correct flag on rewrite
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

                // Build the line ensuring exactly 15 columns
                String line = String.join(",",
                        safe(row, 0),  // date
                        safe(row, 1),  // season
                        safe(row, 2),  // home
                        safe(row, 3),  // away
                        safe(row, 4),  // predHomeGoals
                        safe(row, 5),  // predAwayGoals
                        safe(row, 6),  // homeWinProb
                        safe(row, 7),  // drawProb
                        safe(row, 8),  // awayWinProb
                        safe(row, 9),  // predictedOutcome
                        safe(row, 10), // homeElo
                        safe(row, 11), // awayElo
                        safe(row, 12), // (unused placeholder)
                        safe(row, 13), // actualHomeGoals
                        correct        // correct TRUE/FALSE
                ) + "\n";

                writer.write(line);
            }
        } catch (Exception e) {
            System.out.println("[RESULTS] Error rewriting CSV: " + e.getMessage());
        }
    }

    private static String safe(String[] row, int index) {
        if (index >= row.length) return "";
        return row[index] != null ? row[index].trim() : "";
    }

    /**
     * Simple CSV line parser — handles quoted fields containing commas.
     */
    private static String[] parseCSVLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}