package org.example.files;

/**
 * Lightweight test runner — only used when GitHub Actions
 * is triggered manually with mode = "test".
 *
 * Does NOT call any football APIs so costs zero API requests.
 * Just sends a Telegram message to confirm the bot is working.
 */
public class TestRunner {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("      SOCCER PREDICTOR — CONNECTION TEST");
        System.out.println("==============================================\n");

        System.out.println("[TEST] Sending Telegram test message...");
        TelegramNotifier.sendTest();

        System.out.println("\n[TEST] Done. Check your Telegram for the message.");
        System.out.println("       If nothing arrived check:");
        System.out.println("       1. TELEGRAM_BOT_TOKEN secret is set correctly");
        System.out.println("       2. TELEGRAM_CHAT_ID secret is set correctly");
        System.out.println("       3. You have started a chat with your bot on Telegram");
        System.out.println("==============================================");
    }
}