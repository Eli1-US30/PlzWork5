package org.example.files;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sends prediction summaries to a Telegram bot.
 *
 * Reads bot token and chat ID from environment variables:
 *   TELEGRAM_BOT_TOKEN — set as GitHub Actions secret
 *   TELEGRAM_CHAT_ID   — set as GitHub Actions secret
 *
 * If either variable is missing the notifier skips silently
 * so local runs are unaffected.
 *
 * Telegram message format uses MarkdownV2:
 *   *bold*, `code`, plain text
 */
public class TelegramNotifier {

    private static final String BASE_URL = "https://api.telegram.org/bot";

    // Read from environment — never hardcode these
    private static final String BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String CHAT_ID   = System.getenv("TELEGRAM_CHAT_ID");

    /**
     * Sends a simple test message to verify the bot is working.
     * Call this from a test run before the full predictor.
     */
    public static void sendTest() {
        send("⚽ *Soccer Predictor* is connected and working\\!\n\nYou'll receive predictions here each gameweek\\.");
    }

    /**
     * Sends the full gameweek prediction summary.
     *
     * @param matches     list of upcoming matches
     * @param predictions list of final blended predictions (same order as matches)
     * @param blendedList list of blended probability objects for over/under
     */
    public static void sendPredictions(
            List<Match> matches,
            List<Prediction> predictions,
            List<OddsBlender.BlendedProbabilities> blendedList) {

        if (predictions.isEmpty()) {
            send("⚽ *Soccer Predictor*\n\nNo predictions generated this gameweek\\.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("⚽ *Premier League Predictions*\n");
        sb.append(escapeMarkdown("─────────────────────")).append("\n\n");

        for (int i = 0; i < predictions.size(); i++) {
            Prediction p = predictions.get(i);
            OddsBlender.BlendedProbabilities b = blendedList.get(i);

            // Match header
            sb.append("🏟 *")
                    .append(escapeMarkdown(p.getHomeTeam()))
                    .append(" vs ")
                    .append(escapeMarkdown(p.getAwayTeam()))
                    .append("*\n");

            // Predicted score
            sb.append("📊 Score: `")
                    .append(p.getHomeGoals())
                    .append(" \\- ")
                    .append(p.getAwayGoals())
                    .append("`\n");

            // Probabilities
            sb.append("🏠 Home Win: *").append(String.format("%.1f", p.getHomeWinProb())).append("%*  ");
            sb.append("🤝 Draw: *").append(String.format("%.1f", p.getDrawProb())).append("%*  ");
            sb.append("✈️ Away Win: *").append(String.format("%.1f", p.getAwayWinProb())).append("%*\n");

            // Over/under
            sb.append("⚽ Over 2\\.5: *")
                    .append(String.format("%.1f", b.over25Prob))
                    .append("%*\n");

            // Most likely outcome
            sb.append("✅ Prediction: *")
                    .append(escapeMarkdown(p.getOutcome()))
                    .append("*");

            if (b.oddsUsed) {
                sb.append(" \\(blended\\)");
            }

            sb.append("\n\n");
        }

        sb.append(escapeMarkdown("─────────────────────")).append("\n");
        sb.append("_Predictions update each gameweek_");

        send(sb.toString());
    }

    /**
     * Sends a plain notification — used for errors or status updates.
     */
    public static void sendAlert(String message) {
        send("⚠️ *Soccer Predictor Alert*\n\n" + escapeMarkdown(message));
    }

    /**
     * Core send method — posts a MarkdownV2 message to the Telegram Bot API.
     */
    private static void send(String markdownText) {
        if (BOT_TOKEN == null || BOT_TOKEN.isBlank()) {
            System.out.println("[TELEGRAM] BOT_TOKEN not set — skipping notification");
            return;
        }
        if (CHAT_ID == null || CHAT_ID.isBlank()) {
            System.out.println("[TELEGRAM] CHAT_ID not set — skipping notification");
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
            if (status == 200) {
                System.out.println("[TELEGRAM] Message sent successfully");
            } else {
                System.out.println("[TELEGRAM] Failed to send message — HTTP " + status);
            }

        } catch (Exception e) {
            System.out.println("[TELEGRAM] Error sending message: " + e.getMessage());
        }
    }

    /**
     * Escapes special characters for Telegram MarkdownV2 format.
     * Required characters: _ * [ ] ( ) ~ ` > # + - = | { } . !
     */
    private static String escapeMarkdown(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("_",  "\\_")
                .replace("*",  "\\*")
                .replace("[",  "\\[")
                .replace("]",  "\\]")
                .replace("(",  "\\(")
                .replace(")",  "\\)")
                .replace("~",  "\\~")
                .replace("`",  "\\`")
                .replace(">",  "\\>")
                .replace("#",  "\\#")
                .replace("+",  "\\+")
                .replace("-",  "\\-")
                .replace("=",  "\\=")
                .replace("|",  "\\|")
                .replace("{",  "\\{")
                .replace("}",  "\\}")
                .replace(".",  "\\.")
                .replace("!",  "\\!");
    }
}