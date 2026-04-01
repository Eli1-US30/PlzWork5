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

/**
 * Sends four types of Telegram messages:
 *
 * 1. sendNoGamesToday()   — hourly check found no games today
 * 2. sendGamesToday()     — games found today, lists kickoff times in SA time
 * 3. sendPredictions()    — full pre-match prediction 30min before kickoff
 * 4. sendResult()         — post-match result vs what we predicted
 */
public class TelegramNotifier {

    private static final String BASE_URL  = "https://api.telegram.org/bot";
    private static final String BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String CHAT_ID   = System.getenv("TELEGRAM_CHAT_ID");
    private static final ZoneId SA_ZONE   = ZoneId.of("Africa/Johannesburg");

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
    // MESSAGE 3 — full pre-match prediction
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

            sb.append("✅ Prediction: *")
                    .append(escapeMarkdown(p.getOutcome())).append("*");

            if (b.oddsUsed) sb.append(" \\(odds blended\\)");
            sb.append("\n\n");
        }

        sb.append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("_Good luck\\!_ 🍀");
        send(sb.toString());
    }

    // -----------------------------------------------------------
    // MESSAGE 4 — post-match result vs prediction
    // -----------------------------------------------------------
    public static void sendResult(String homeTeam, String awayTeam,
                                  int actualHome, int actualAway,
                                  String predictedOutcome,
                                  int predictedHome, int predictedAway) {

        String actualOutcome = actualHome > actualAway
                ? homeTeam + " Win"
                : actualAway > actualHome ? awayTeam + " Win" : "Draw";

        boolean correct = predictedOutcome.equalsIgnoreCase(actualOutcome);
        String  icon    = correct ? "✅" : "❌";

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
        sb.append(icon).append(" *").append(correct ? "CORRECT" : "INCORRECT").append("*");

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
                + "🏁 Post\\-match results");
    }

    public static void sendAlert(String message) {
        send("⚠️ *Alert*\n\n" + escapeMarkdown(message));
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