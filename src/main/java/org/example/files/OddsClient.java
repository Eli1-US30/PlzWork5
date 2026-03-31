package org.example.files;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Fetches Premier League odds from The Odds API and converts them into
 * FAIR probabilities (overround removed) ready to blend with our model.
 *
 * WHY REMOVE THE OVERROUND:
 *   Raw implied probabilities from odds always sum to > 100% because the
 *   bookmaker builds in a margin (typically 5-8%). If we blend raw implied
 *   probs with our model we're mixing clean probabilities with inflated ones.
 *   Stripping the overround normalises them to sum to 100% first.
 *
 *   Example:
 *     Home 2.10 → raw implied 47.6%
 *     Draw 3.40 → raw implied 29.4%
 *     Away 3.60 → raw implied 27.8%
 *     Total = 104.8% → overround = 4.8%
 *     After normalising: Home 45.4%, Draw 28.1%, Away 26.5% (sum = 100%)
 *
 * Sign up free at https://the-odds-api.com — replace YOUR_ODDS_API_KEY below.
 */
public class OddsClient {

    private static final String API_KEY = System.getenv("ODDS_API_KEY");
    private static final String BASE_URL =
            "https://api.the-odds-api.com/v4/sports/soccer_epl/odds/";

    // Key: normalised match key → OddsData
    private static final Map<String, OddsData> cache = new HashMap<>();
    private static boolean fetched = false;

    /**
     * Fetch all available PL odds and cache them.
     * Call once at startup — costs one API request.
     */
    public static void preload() {
        if (fetched) return;

        try {
            String urlStr = BASE_URL
                    + "?apiKey=" + API_KEY
                    + "&regions=uk"
                    + "&markets=h2h,totals"
                    + "&oddsFormat=decimal"
                    + "&dateFormat=iso";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int status = conn.getResponseCode();
            System.out.println("[ODDS] HTTP status: " + status);

            String remaining = conn.getHeaderField("x-requests-remaining");
            String used      = conn.getHeaderField("x-requests-used");
            if (remaining != null)
                System.out.printf("[ODDS] Requests this month: used=%s remaining=%s%n",
                        used, remaining);

            if (status != 200) {
                System.out.println("[ODDS] Could not fetch odds — model will run without blending");
                fetched = true;
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            parseAndCache(new JSONArray(sb.toString()));
            System.out.println("[ODDS] Loaded and normalised odds for "
                    + cache.size() + " matches");

        } catch (Exception e) {
            System.out.println("[ODDS] Error fetching odds: " + e.getMessage()
                    + " — model will run without blending");
        }

        fetched = true;
    }

    private static void parseAndCache(JSONArray events) {
        for (int i = 0; i < events.length(); i++) {
            JSONObject event     = events.getJSONObject(i);
            String homeTeam      = event.getString("home_team");
            String awayTeam      = event.getString("away_team");

            JSONArray bookmakers = event.optJSONArray("bookmakers");
            if (bookmakers == null || bookmakers.isEmpty()) continue;

            // Use first available bookmaker
            JSONObject bookmaker  = bookmakers.getJSONObject(0);
            String bookmakerName  = bookmaker.getString("key");
            JSONArray markets     = bookmaker.getJSONArray("markets");

            OddsData oddsData = new OddsData(homeTeam, awayTeam, bookmakerName);

            for (int m = 0; m < markets.length(); m++) {
                JSONObject market  = markets.getJSONObject(m);
                String marketKey   = market.getString("key");
                JSONArray outcomes = market.getJSONArray("outcomes");

                if (marketKey.equals("h2h")) {
                    // Step 1: collect raw implied probs
                    double rawHome = 0, rawDraw = 0, rawAway = 0;
                    double rawHomeOdds = -1, rawDrawOdds = -1, rawAwayOdds = -1;

                    for (int o = 0; o < outcomes.length(); o++) {
                        JSONObject outcome = outcomes.getJSONObject(o);
                        String name  = outcome.getString("name");
                        double price = outcome.getDouble("price");

                        if (name.equalsIgnoreCase(homeTeam)) {
                            rawHome = 1.0 / price;
                            rawHomeOdds = price;
                        } else if (name.equalsIgnoreCase(awayTeam)) {
                            rawAway = 1.0 / price;
                            rawAwayOdds = price;
                        } else if (name.equalsIgnoreCase("Draw")) {
                            rawDraw = 1.0 / price;
                            rawDrawOdds = price;
                        }
                    }

                    // Step 2: strip overround by normalising to sum to 1.0
                    double total = rawHome + rawDraw + rawAway;
                    if (total > 0) {
                        oddsData.fairHomeWinProb  = (rawHome / total) * 100.0;
                        oddsData.fairDrawProb     = (rawDraw / total) * 100.0;
                        oddsData.fairAwayWinProb  = (rawAway / total) * 100.0;
                        oddsData.overround        = (total - 1.0) * 100.0;

                        System.out.printf("[ODDS] %s vs %s  overround=%.1f%%  " +
                                        "fair: H=%.1f%% D=%.1f%% A=%.1f%%%n",
                                homeTeam, awayTeam, oddsData.overround,
                                oddsData.fairHomeWinProb,
                                oddsData.fairDrawProb,
                                oddsData.fairAwayWinProb);
                    }

                    oddsData.homeWinOdds = rawHomeOdds;
                    oddsData.drawOdds    = rawDrawOdds;
                    oddsData.awayWinOdds = rawAwayOdds;

                } else if (marketKey.equals("totals")) {
                    // Over/under: normalise each line pair (over+under should sum ~100%)
                    Map<Double, Double> rawOver  = new HashMap<>();
                    Map<Double, Double> rawUnder = new HashMap<>();

                    for (int o = 0; o < outcomes.length(); o++) {
                        JSONObject outcome = outcomes.getJSONObject(o);
                        String name   = outcome.getString("name");
                        double point  = outcome.getDouble("point");
                        double price  = outcome.getDouble("price");

                        if (name.equalsIgnoreCase("Over"))
                            rawOver.put(point, 1.0 / price);
                        else
                            rawUnder.put(point, 1.0 / price);
                    }

                    // Normalise each line pair
                    for (double line : rawOver.keySet()) {
                        double o = rawOver.getOrDefault(line, 0.0);
                        double u = rawUnder.getOrDefault(line, 0.0);
                        double t = o + u;
                        if (t > 0) {
                            oddsData.fairOverProb.put(line,  (o / t) * 100.0);
                            oddsData.fairUnderProb.put(line, (u / t) * 100.0);
                        }
                    }
                }
            }

            // Store under a normalised key for fuzzy matching
            cache.put(normalise(homeTeam) + "|" + normalise(awayTeam), oddsData);
        }
    }

    /**
     * Returns OddsData for a match, or null if not available.
     * Uses fuzzy matching to handle name differences between APIs.
     */
    public static OddsData getOdds(String homeTeam, String awayTeam) {
        String key = normalise(homeTeam) + "|" + normalise(awayTeam);
        if (cache.containsKey(key)) return cache.get(key);

        // Fuzzy: check if cached team names contain our team names
        String normHome = normalise(homeTeam);
        String normAway = normalise(awayTeam);

        for (Map.Entry<String, OddsData> entry : cache.entrySet()) {
            String cacheHome = normalise(entry.getValue().homeTeam);
            String cacheAway = normalise(entry.getValue().awayTeam);
            if (cacheHome.contains(normHome) || normHome.contains(cacheHome)) {
                if (cacheAway.contains(normAway) || normAway.contains(cacheAway)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** Strips FC, lowercase, trim for fuzzy matching */
    private static String normalise(String name) {
        return name.toLowerCase()
                .replace(" fc", "")
                .replace(" afc", "")
                .replace(" &", "")
                .trim();
    }

    // -------------------------------------------------------------------------

    public static class OddsData {
        public final String homeTeam;
        public final String awayTeam;
        public final String bookmaker;

        // Raw decimal odds (kept for display)
        public double homeWinOdds = -1;
        public double drawOdds    = -1;
        public double awayWinOdds = -1;

        // Fair probabilities (overround removed), 0-100
        public double fairHomeWinProb = -1;
        public double fairDrawProb    = -1;
        public double fairAwayWinProb = -1;
        public double overround       = 0;

        // Fair over/under probabilities keyed by line (1.5, 2.5, 3.5)
        public final Map<Double, Double> fairOverProb  = new HashMap<>();
        public final Map<Double, Double> fairUnderProb = new HashMap<>();

        public OddsData(String homeTeam, String awayTeam, String bookmaker) {
            this.homeTeam  = homeTeam;
            this.awayTeam  = awayTeam;
            this.bookmaker = bookmaker;
        }

        public boolean hasMatchOdds() {
            return fairHomeWinProb >= 0 && fairDrawProb >= 0 && fairAwayWinProb >= 0;
        }

        public boolean hasOverUnder(double line) {
            return fairOverProb.containsKey(line);
        }
    }
}