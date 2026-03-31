package org.example.files;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class FPLClient {

    private static final String BASE_URL =
            "https://fantasy.premierleague.com/api/";

    // Cache the response so we only call once per run
    private static JSONObject cachedData = null;

    public static JSONObject getBootstrapStatic() {
        if (cachedData != null) {
            System.out.println("[FPL] Using cached bootstrap data");
            return cachedData;
        }

        try {
            URL url = new URL(BASE_URL + "bootstrap-static/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // FPL requires a User-Agent header or it blocks the request
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0");
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int status = conn.getResponseCode();
            System.out.println("[FPL] Status: " + status);

            if (status != 200) {
                System.out.println("[FPL] Failed to fetch bootstrap data");
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                response.append(line);
            reader.close();

            cachedData = new JSONObject(response.toString());
            System.out.println("[FPL] Bootstrap data fetched successfully");
            return cachedData;

        } catch (Exception e) {
            System.out.println("[FPL] Error fetching bootstrap: "
                    + e.getMessage());
            return null;
        }
    }
}