package org.example.files;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;

/**
 * Logs every prediction to a CSV file before matches are played.
 *
 * File: ./data/predictions.csv
 * Opens fine in Excel — one row per match prediction.
 *
 * Columns:
 *   date, season, home, away,
 *   predHomeGoals, predAwayGoals,
 *   homeWinProb, drawProb, awayWinProb,
 *   predictedOutcome,
 *   homeEloAtPrediction, awayEloAtPrediction
 *
 * The result columns (actualHomeGoals, actualAwayGoals, correct)
 * are left blank here and filled in later by ResultTracker.
 */
public class PredictionLogger {

    private static final String FILE_PATH = "data/predictions.csv";

    private static final String HEADER =
            "date,season,home,away," +
                    "predHomeGoals,predAwayGoals," +
                    "homeWinProb,drawProb,awayWinProb," +
                    "predictedOutcome," +
                    "homeEloAtPrediction,awayEloAtPrediction," +
                    "actualHomeGoals,actualAwayGoals,correct\n";

    /**
     * Call once at startup to ensure the CSV file and header exist.
     */
    public static void init() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            try (FileWriter writer = new FileWriter(FILE_PATH)) {
                writer.write(HEADER);
                System.out.println("[LOG] Created predictions log: " + FILE_PATH);
            } catch (Exception e) {
                System.out.println("[LOG] Could not create predictions file: " + e.getMessage());
            }
        } else {
            System.out.println("[LOG] Predictions log found: " + FILE_PATH);
        }
    }

    /**
     * Log a prediction before the match is played.
     * The actual result columns are left blank — ResultTracker fills them in.
     *
     * @param prediction  the Prediction object from Simulator
     * @param home        the home Team object (for ELO at time of prediction)
     * @param away        the away Team object
     */
    public static void log(Prediction prediction, Team home, Team away) {
        String date   = LocalDate.now().toString();
        String season = getCurrentSeason();

        String line = String.format("%s,%s,%s,%s,%d,%d,%.1f,%.1f,%.1f,%s,%.0f,%.0f,,,%n",
                date,
                season,
                csvSafe(prediction.getHomeTeam()),
                csvSafe(prediction.getAwayTeam()),
                prediction.getHomeGoals(),
                prediction.getAwayGoals(),
                prediction.getHomeWinProb(),
                prediction.getDrawProb(),
                prediction.getAwayWinProb(),
                csvSafe(prediction.getOutcome()),
                home.getElo(),
                away.getElo()
        );

        try (FileWriter writer = new FileWriter(FILE_PATH, true)) { // append mode
            writer.write(line);
        } catch (Exception e) {
            System.out.println("[LOG] Failed to write prediction: " + e.getMessage());
        }
    }

    /**
     * Returns current season string e.g. "2025-26"
     */
    private static String getCurrentSeason() {
        int year = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();
        // PL season runs Aug-May so if we're Jan-May we're in the season that started last year
        if (month <= 7) {
            return (year - 1) + "-" + String.valueOf(year).substring(2);
        } else {
            return year + "-" + String.valueOf(year + 1).substring(2);
        }
    }

    /**
     * Wraps a value in quotes if it contains a comma (safe for CSV).
     */
    private static String csvSafe(String value) {
        if (value != null && value.contains(",")) {
            return "\"" + value + "\"";
        }
        return value != null ? value : "";
    }
}