package com.custom.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the home screen. Location comes from the TV's public IP (city level), the
 * weather from Open-Meteo; neither needs an account or key. The last reading is kept in
 * SharedPreferences so the screen (and the Home overlay, which must match it) shows weather
 * immediately after a reboot. These are the only network requests the launcher makes.
 */
final class Weather {

    enum Kind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

    static final class Now {
        final Kind kind;
        final boolean day;
        final int temperature;
        final String unit;
        final String place;
        /** Rain or snow starting (or, if stopping, ending) at changeAt; null when nothing changes soon. */
        final Kind change;
        final boolean stopping;
        final long changeAt;

        Now(Kind kind, boolean day, int temperature, String unit, String place,
                Kind change, boolean stopping, long changeAt) {
            this.kind = kind;
            this.day = day;
            this.temperature = temperature;
            this.unit = unit;
            this.place = place;
            this.change = change;
            this.stopping = stopping;
            this.changeAt = changeAt;
        }

        /** "Rain in 45 min", "Snow in 1 hour", "Rain stopping in 30 min", or null. */
        String outlook(long nowMs) {
            if (change == null) return null;
            String what = change == Kind.SNOW ? "Snow" : "Rain";
            long minutes = Math.round((changeAt - nowMs) / 60000.0);
            if (minutes < -15) return null;
            if (minutes <= 5) return what + (stopping ? " stopping soon" : " starting soon");
            String when;
            if (minutes < 55) when = (Math.round(minutes / 5.0) * 5) + " min";
            else if (minutes < 90) when = "1 hour";
            else when = Math.round(minutes / 60.0) + " hours";
            return what + (stopping ? " stopping in " : " in ") + when;
        }

        String condition() {
            switch (kind) {
                case CLEAR: return day ? "Sunny" : "Clear";
                case PARTLY_CLOUDY: return "Partly cloudy";
                case CLOUDY: return "Cloudy";
                case FOG: return "Foggy";
                case RAIN: return "Rain";
                case SNOW: return "Snow";
                default: return "Thunderstorms";
            }
        }
    }

    interface Listener {
        void onWeather(Now now);
    }

    private static final long WEATHER_MAX_AGE_MS = 20 * 60 * 1000;
    private static final long LOCATION_MAX_AGE_MS = 12 * 60 * 60 * 1000;
    private static final long RETRY_AFTER_MS = 30 * 1000;
    private static final int TIMEOUT_MS = 8000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final List<Listener> LISTENERS = new ArrayList<>();

    private static Now current;
    private static boolean loaded;
    private static boolean fetching;
    private static long lastAttempt = -RETRY_AFTER_MS;

    private Weather() {
    }

    /** The latest reading, or null if there has never been one. Main thread only. */
    static Now current(Context context) {
        if (!loaded) {
            loaded = true;
            current = readCached(prefs(context));
        }
        return current;
    }

    static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** Fetches in the background if the reading is old. Main thread only. */
    static void refreshIfStale(Context context) {
        final Context app = context.getApplicationContext();
        current(app);
        final SharedPreferences prefs = prefs(app);
        long age = System.currentTimeMillis() - prefs.getLong("fetchedAt", 0);
        long now = SystemClock.elapsedRealtime();
        if (fetching || age < WEATHER_MAX_AGE_MS || now - lastAttempt < RETRY_AFTER_MS) return;
        fetching = true;
        lastAttempt = now;
        NET.execute(new Runnable() {
            @Override
            public void run() {
                final Now result = fetch(prefs);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        fetching = false;
                        if (result == null) return;
                        current = result;
                        for (Listener l : new ArrayList<>(LISTENERS)) l.onWeather(result);
                    }
                });
            }
        });
    }

    /** Network thread: refreshes the location if needed, then the weather. Null on failure. */
    private static Now fetch(SharedPreferences prefs) {
        try {
            long nowMs = System.currentTimeMillis();
            if (nowMs - prefs.getLong("locatedAt", 0) > LOCATION_MAX_AGE_MS || !prefs.contains("lat")) {
                locate(prefs);
            }
            String lat = prefs.getString("lat", null);
            String lon = prefs.getString("lon", null);
            if (lat == null || lon == null) return null;
            boolean fahrenheit = usesFahrenheit(prefs.getString("country", ""));
            JSONObject json = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day"
                    + "&minutely_15=rain,snowfall&forecast_minutely_15=12"
                    + (fahrenheit ? "&temperature_unit=fahrenheit" : "") + "&timezone=auto&timeformat=unixtime"));
            JSONObject cur = json.getJSONObject("current");
            Kind kind = kindFor(cur.getInt("weather_code"));
            Kind change = null;
            boolean stopping = false;
            long changeAt = 0;
            JSONObject quarter = json.optJSONObject("minutely_15");
            if (quarter != null) {
                // Next three hours in 15-minute steps: when does it start (or stop) raining or snowing?
                boolean wetNow = kind == Kind.RAIN || kind == Kind.SNOW || kind == Kind.THUNDER;
                JSONArray times = quarter.getJSONArray("time");
                JSONArray rain = quarter.getJSONArray("rain");
                JSONArray snow = quarter.getJSONArray("snowfall");
                for (int i = 0; i < times.length(); i++) {
                    long at = times.getLong(i) * 1000;
                    if (at + 15 * 60 * 1000 <= nowMs) continue;
                    double mm = rain.optDouble(i, 0);
                    double cm = snow.optDouble(i, 0);
                    boolean wet = mm >= 0.1 || cm >= 0.02;
                    if (!wetNow && wet) {
                        // 1 cm of snow is roughly 1 mm of water, times ~7 for fresh snow's volume.
                        change = cm * 7 >= mm ? Kind.SNOW : Kind.RAIN;
                        changeAt = Math.max(at, nowMs);
                        break;
                    }
                    if (wetNow && !wet) {
                        change = kind == Kind.SNOW ? Kind.SNOW : Kind.RAIN;
                        stopping = true;
                        changeAt = at;
                        break;
                    }
                }
            }
            Now result = new Now(kind, cur.optInt("is_day", 1) == 1,
                    (int) Math.round(cur.getDouble("temperature_2m")), fahrenheit ? "F" : "C",
                    prefs.getString("place", ""), change, stopping, changeAt);
            prefs.edit()
                    .putString("kind", result.kind.name())
                    .putBoolean("day", result.day)
                    .putInt("temp", result.temperature)
                    .putString("unit", result.unit)
                    .putString("change", change == null ? null : change.name())
                    .putBoolean("stopping", stopping)
                    .putLong("changeAt", changeAt)
                    .putLong("fetchedAt", nowMs)
                    .apply();
            return result;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** City-level location from the public IP, with a second provider as fallback. */
    private static void locate(SharedPreferences prefs) throws IOException, JSONException {
        JSONObject j;
        String lat;
        String lon;
        String country;
        try {
            j = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
            lat = j.getString("latitude");
            lon = j.getString("longitude");
            country = j.optString("country_code");
        } catch (IOException | JSONException e) {
            j = new JSONObject(get("https://ipapi.co/json/"));
            lat = String.valueOf(j.getDouble("latitude"));
            lon = String.valueOf(j.getDouble("longitude"));
            country = j.optString("country_code");
        }
        prefs.edit()
                .putString("lat", lat)
                .putString("lon", lon)
                .putString("place", j.optString("city", ""))
                .putString("country", country)
                .putLong("locatedAt", System.currentTimeMillis())
                .apply();
    }

    private static Now readCached(SharedPreferences prefs) {
        String kind = prefs.getString("kind", null);
        if (kind == null) return null;
        try {
            String change = prefs.getString("change", null);
            return new Now(Kind.valueOf(kind), prefs.getBoolean("day", true), prefs.getInt("temp", 0),
                    prefs.getString("unit", "F"), prefs.getString("place", ""),
                    change == null ? null : Kind.valueOf(change), prefs.getBoolean("stopping", false),
                    prefs.getLong("changeAt", 0));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    private static Kind kindFor(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code <= 2) return Kind.PARTLY_CLOUDY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.THUNDER;
        return Kind.RAIN;
    }

    private static boolean usesFahrenheit(String country) {
        if (country.isEmpty()) country = Locale.getDefault().getCountry();
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "TVHome/1.0");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
