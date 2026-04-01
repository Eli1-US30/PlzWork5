package org.example.files;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Sends five types of Telegram messages:
 *
 * 1. sendNoGamesToday()       — hourly check found no games today
 * 2. sendGamesToday()         — games found today, lists kickoff times in SA time
 * 3. sendPredictions()        — full pre-match prediction 30min before kickoff
 *                               now includes confidence indicator (feature 7)
 * 4. sendResult()             — post-match result vs what we predicted
 *                               now flags blowouts (margin ≥ 3, wrong prediction)
 * 5. sendMonthlyAccuracy()    — first run of each month, season accuracy summary
 *                               with per-team breakdown and flagging (feature 9)
 */
public class TelegramNotifier {

    private static final String BASE_URL  = "https://api.telegram.org/bot";
    private static final String BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String CHAT_ID   = System.getenv("TELEGRAM_CHAT_ID");
    private static final ZoneId SA_ZONE   = ZoneId.of("Africa/Johannesburg");

    // Confidence thresholds (feature 7)
    private static final double STRONG_THRESHOLD = 65.0;  // 🔥 one outcome > 65%
    private static final double TIGHT_THRESHOLD  = 10.0;  // ⚖️ gap between top two < 10%

    // Team accuracy flag threshold (feature 9)
    private static final double FLAG_THRESHOLD   = 40.0;  // ⚠️ below 40% accuracy

    // Blowout threshold (feature 9 + result message)
    private static final int BLOWOUT_MARGIN      = 3;     // 🌪️ goal difference ≥ 3

    // -----------------------------------------------------------
    // MESSAGE 1 — no games today
    // -----------------------------------------------------------
    public static void sendNoGamesToday() {
        String time = ZonedDateTime.now(SA_ZONE)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        send("🔍 *Soccer Predictor Check*\n\n"
                + "Checked at " + escapeMarkdown(time) + " SA time\\.\n"
                + "No Premier League games today\\.");
    }

    // -----------------------------------------------------------
    // MESSAGE 2 — games found today
    // -----------------------------------------------------------
    public static void sendGamesToday(List<UpcomingGame> games) {
        StringBuilder sb = new StringBuilder();
        sb.append("📅 *Premier League Today*\n\n");
        sb.append(escapeMarkdown("─────────────────────")).append("\n");

        for (UpcomingGame game : games) {
            sb.append("\n⚽ *")
                    .append(escapeMarkdown(game.homeTeam))
                    .append(" vs ")
                    .append(escapeMarkdown(game.awayTeam))
                    .append("*\n");
            sb.append("🕐 Kickoff: *")
                    .append(escapeMarkdown(game.kickoffTimeSA))
                    .append(" SA time*\n");
        }

        sb.append("\n").append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("_Predictions arrive 30 minutes before each kickoff_");
        send(sb.toString());
    }

    // -----------------------------------------------------------
    // MESSAGE 3 — full pre-match prediction (with confidence indicator)
    // -----------------------------------------------------------
    public static void sendPredictions(
            List<Match> matches,
            List<Prediction> predictions,
            List<OddsBlender.BlendedProbabilities> blendedList) {

        if (predictions.isEmpty()) {
            send("⚽ *Soccer Predictor*\n\nNo predictions generated\\.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("⚽ *Pre\\-Match Predictions*\n");
        sb.append(escapeMarkdown("─────────────────────")).append("\n\n");

        for (int i = 0; i < predictions.size(); i++) {
            Prediction p = predictions.get(i);
            OddsBlender.BlendedProbabilities b = blendedList.get(i);

            sb.append("🏟 *")
                    .append(escapeMarkdown(p.getHomeTeam()))
                    .append(" vs ")
                    .append(escapeMarkdown(p.getAwayTeam()))
                    .append("*\n");

            sb.append("📊 Predicted Score: `")
                    .append(p.getHomeGoals())
                    .append(" \\- ")
                    .append(p.getAwayGoals())
                    .append("`\n");

            // Probabilities with confidence indicator
            sb.append("🏠 Home Win: *")
                    .append(String.format("%.1f", p.getHomeWinProb())).append("%*  ");
            sb.append("🤝 Draw: *")
                    .append(String.format("%.1f", p.getDrawProb())).append("%*  ");
            sb.append("✈️ Away Win: *")
                    .append(String.format("%.1f", p.getAwayWinProb())).append("%*\n");

            sb.append("⚽ Over 1\\.5: *")
                    .append(String.format("%.1f", b.over15Prob)).append("%*  ");
            sb.append("Over 2\\.5: *")
                    .append(String.format("%.1f", b.over25Prob)).append("%*  ");
            sb.append("Over 3\\.5: *")
                    .append(String.format("%.1f", b.over35Prob)).append("%*\n");

            // Confidence indicator (feature 7)
            String confidenceLabel = getConfidenceLabel(
                    p.getHomeWinProb(), p.getDrawProb(), p.getAwayWinProb());

            sb.append(escapeMarkdown(confidenceLabel)).append(" Prediction: *")
                    .append(escapeMarkdown(p.getOutcome())).append("*");

            if (b.oddsUsed) sb.append(" \\(odds blended\\)");
            sb.append("\n\n");
        }

        sb.append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("_Good luck\\!_ 🍀");
        send(sb.toString());
    }

    // -----------------------------------------------------------
    // MESSAGE 4 — post-match result (with blowout flag)
    // -----------------------------------------------------------
    public static void sendResult(String homeTeam, String awayTeam,
                                  int actualHome, int actualAway,
                                  String predictedOutcome,
                                  int predictedHome, int predictedAway) {

        String actualOutcome = actualHome > actualAway
                ? homeTeam + " Win"
                : actualAway > actualHome ? awayTeam + " Win" : "Draw";

        boolean correct  = predictedOutcome.equalsIgnoreCase(actualOutcome);
        int     margin   = Math.abs(actualHome - actualAway);
        boolean blowout  = !correct && margin >= BLOWOUT_MARGIN;

        String icon = correct ? "✅" : (blowout ? "🌪️" : "❌");

        StringBuilder sb = new StringBuilder();
        sb.append("🏁 *Match Result*\n\n");
        sb.append("🏟 *")
                .append(escapeMarkdown(homeTeam))
                .append(" vs ")
                .append(escapeMarkdown(awayTeam))
                .append("*\n\n");

        sb.append("📊 Actual Score:    `")
                .append(actualHome).append(" \\- ").append(actualAway).append("`\n");
        sb.append("🔮 Predicted Score: `")
                .append(predictedHome).append(" \\- ").append(predictedAway).append("`\n\n");

        sb.append("Result:       *").append(escapeMarkdown(actualOutcome)).append("*\n");
        sb.append("We predicted: *").append(escapeMarkdown(predictedOutcome)).append("*\n\n");
        sb.append(icon).append(" *")
                .append(correct ? "CORRECT" : "INCORRECT").append("*");

        // Blowout explanation
        if (blowout) {
            sb.append("\n_").append(escapeMarkdown(
                            "Blowout result (margin " + margin + ") — unpredictable"))
                    .append("_");
        }

        send(sb.toString());
    }

    // -----------------------------------------------------------
    // MESSAGE 5 — monthly accuracy summary (feature 9)
    // Called on first run of each month from Main.java
    // -----------------------------------------------------------
    public static void sendMonthlyAccuracy(List<String[]> csvRows) {
        // Overall stats
        int total = 0, correct = 0, blowouts = 0;

        // Per-team stats: teamName -> [correct, total]
        Map<String, int[]> teamStats = new java.util.LinkedHashMap<>();

        String month = ZonedDateTime.now(SA_ZONE)
                .format(DateTimeFormatter.ofPattern("MMMM yyyy"));

        for (String[] row : csvRows) {
            if (row.length < 15 || row[0].equals("date")) continue;

            String actualHomeStr = row[13].trim();
            String actualAwayStr = row[14].trim();
            if (actualHomeStr.isEmpty() || actualAwayStr.isEmpty()) continue;

            try {
                int ah = Integer.parseInt(actualHomeStr);
                int aa = Integer.parseInt(actualAwayStr);
                String home      = row[2].replace("\"", "").trim();
                String away      = row[3].replace("\"", "").trim();
                String predicted = row[9].replace("\"", "").trim();

                String actualOutcome = ah > aa ? home + " Win"
                        : aa > ah ? away + " Win" : "Draw";
                boolean isCorrect = predicted.equalsIgnoreCase(actualOutcome);
                boolean isBlowout = !isCorrect && Math.abs(ah - aa) >= BLOWOUT_MARGIN;

                total++;
                if (isCorrect) correct++;
                if (isBlowout) blowouts++;

                // Track per team (both home and away)
                for (String teamName : new String[]{home, away}) {
                    teamStats.computeIfAbsent(teamName, k -> new int[]{0, 0});
                    teamStats.get(teamName)[1]++; // total
                    if (isCorrect) teamStats.get(teamName)[0]++; // correct
                }

            } catch (Exception ignored) {}
        }

        if (total == 0) {
            send("📊 *Monthly Accuracy Report — " + escapeMarkdown(month) + "*\n\n"
                    + "No completed predictions yet this season\\.");
            return;
        }

        double overallPct = 100.0 * correct / total;

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Monthly Accuracy Report*\n");
        sb.append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("📅 *").append(escapeMarkdown(month)).append("*\n\n");

        sb.append("Overall: *").append(correct).append("/").append(total)
                .append("* correct \\(*")
                .append(String.format("%.1f", overallPct))
                .append("%*\\)\n");

        if (blowouts > 0) {
            sb.append("🌪️ Blowouts excluded from model blame: *")
                    .append(blowouts).append("*\n");
            // Accuracy excluding blowouts
            double adjPct = 100.0 * correct / (total - blowouts);
            sb.append("Adjusted accuracy \\(excl blowouts\\): *")
                    .append(String.format("%.1f", adjPct)).append("%*\n");
        }

        // Find flagged teams (below threshold with at least 3 predictions)
        sb.append("\n").append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("⚠️ *Teams below ")
                .append(String.format("%.0f", FLAG_THRESHOLD))
                .append("% accuracy*\n\n");

        boolean anyFlagged = false;
        // Sort by accuracy ascending to show worst first
        List<Map.Entry<String, int[]>> sorted = new java.util.ArrayList<>(teamStats.entrySet());
        sorted.sort((a, b) -> {
            double pctA = 100.0 * a.getValue()[0] / Math.max(a.getValue()[1], 1);
            double pctB = 100.0 * b.getValue()[0] / Math.max(b.getValue()[1], 1);
            return Double.compare(pctA, pctB);
        });

        for (Map.Entry<String, int[]> entry : sorted) {
            int[] stats = entry.getValue();
            if (stats[1] < 3) continue; // need at least 3 predictions to flag
            double pct = 100.0 * stats[0] / stats[1];
            if (pct < FLAG_THRESHOLD) {
                String flag = pct < 25.0 ? "🚨" : "⚠️";
                sb.append(flag).append(" *")
                        .append(escapeMarkdown(entry.getKey())).append("*: ")
                        .append(stats[0]).append("/").append(stats[1])
                        .append(" \\(*").append(String.format("%.1f", pct)).append("%*\\)\n");
                anyFlagged = true;
            }
        }

        if (!anyFlagged) {
            sb.append("_All teams above threshold — model performing well\\!_ 🎯\n");
        }

        sb.append("\n").append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("_Season data: ").append(total)
                .append(" completed predictions_");

        send(sb.toString());
    }

    // -----------------------------------------------------------
    // Test + alert
    // -----------------------------------------------------------
    public static void sendTest() {
        send("⚽ *Soccer Predictor* is connected\\!\n\n"
                + "You'll receive:\n"
                + "🔍 Hourly checks\n"
                + "📅 Games today alerts\n"
                + "⚽ Pre\\-match predictions\n"
                + "🏁 Post\\-match results\n"
                + "📊 Monthly accuracy reports");
    }

    public static void sendAlert(String message) {
        send("⚠️ *Alert*\n\n" + escapeMarkdown(message));
    }

    // -----------------------------------------------------------
    // Confidence indicator helper (feature 7)
    // Returns emoji + label based on probability distribution
    // -----------------------------------------------------------
    private static String getConfidenceLabel(double homeProb,
                                             double drawProb,
                                             double awayProb) {
        double max    = Math.max(homeProb, Math.max(drawProb, awayProb));
        double second = Math.max(
                Math.min(homeProb, Math.max(drawProb, awayProb)),
                Math.min(drawProb, awayProb));
        double gap    = max - second;

        if (max >= STRONG_THRESHOLD) {
            return "🔥";      // Strong — one outcome clearly dominant
        } else if (gap < TIGHT_THRESHOLD) {
            return "⚖️";     // Tight — outcomes very close
        } else {
            return "📈";     // Moderate — some lean but not strong
        }
    }

    // -----------------------------------------------------------
    // Core HTTP send
    // -----------------------------------------------------------
    private static void send(String markdownText) {
        if (BOT_TOKEN == null || BOT_TOKEN.isBlank()) {
            System.out.println("[TELEGRAM] BOT_TOKEN not set — skipping");
            return;
        }
        if (CHAT_ID == null || CHAT_ID.isBlank()) {
            System.out.println("[TELEGRAM] CHAT_ID not set — skipping");
            return;
        }

        try {
            String urlStr  = BASE_URL + BOT_TOKEN + "/sendMessage";
            String payload = "chat_id=" + URLEncoder.encode(CHAT_ID, StandardCharsets.UTF_8)
                    + "&parse_mode=MarkdownV2"
                    + "&text=" + URLEncoder.encode(markdownText, StandardCharsets.UTF_8);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            System.out.println("[TELEGRAM] " + (status == 200
                    ? "Message sent successfully"
                    : "Failed — HTTP " + status));

        } catch (Exception e) {
            System.out.println("[TELEGRAM] Error: " + e.getMessage());
        }
    }

    private static String escapeMarkdown(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\").replace("_",  "\\_")
                .replace("*",  "\\*") .replace("[",  "\\[")
                .replace("]",  "\\]") .replace("(",  "\\(")
                .replace(")",  "\\)") .replace("~",  "\\~")
                .replace("`",  "\\`") .replace(">",  "\\>")
                .replace("#",  "\\#") .replace("+",  "\\+")
                .replace("-",  "\\-") .replace("=",  "\\=")
                .replace("|",  "\\|") .replace("{",  "\\{")
                .replace("}",  "\\}") .replace(".",  "\\.")
                .replace("!",  "\\!");
    }

    // Simple data class used by sendGamesToday
    public static class UpcomingGame {
        public final String homeTeam;
        public final String awayTeam;
        public final String kickoffTimeSA;

        public UpcomingGame(String homeTeam, String awayTeam, String kickoffTimeSA) {
            this.homeTeam       = homeTeam;
            this.awayTeam       = awayTeam;
            this.kickoffTimeSA  = kickoffTimeSA;
        }
    }
}