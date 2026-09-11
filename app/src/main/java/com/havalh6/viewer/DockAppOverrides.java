package com.havalh6.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Impulse-style per-app dock display overrides: custom name, substitute icon slug,
 * glyph tint, and plate fill. Persisted under {@link #PREF_KEY} in the shell prefs.
 */
final class DockAppOverrides {
    private static final String TAG = "DockAppOverrides";

    static final String GWM_HUB_PKG = "__gwm_hub";
    static final String PREF_KEY = "dock_app_overrides";

    /**
     * Brand marks first (untinted), then the shared geometric set.
     * Labels stay Portuguese to match the rest of the Personalizar sheet.
     */
    static final String[][] SUBSTITUTE_ICONS = {
            {"youtube", "YouTube"},
            {"youtube_music", "YT Music"},
            {"gwm", "GWM"},
            {"nav", "Navegação"},
            {"music", "Música"},
            {"video", "Vídeo"},
            {"radio", "Rádio"},
            {"phone", "Telefone"},
            {"chat", "Chat"},
            {"map_alt", "Mapa"},
            {"browser", "Navegador"},
            {"camera", "Câmera"},
            {"photos", "Fotos"},
            {"settings", "Configurações"},
            {"haval", "Carro"},
            {"bolt", "Carga"},
            {"weather", "Clima"},
            {"calendar", "Agenda"},
            {"clock", "Relógio"},
            {"folder", "Arquivos"},
            {"download", "Download"},
            {"store", "Loja"},
            {"game", "Jogo"},
            {"star", "Estrela"},
            {"tv", "TV"},
            {"mail", "Email"},
            {"wifi", "Rede"},
            {"speaker", "Caixa"},
    };

    /** Tint and plate: white, gray, dark gray, black. First is default tint. */
    static final String[] COLORS = {
            "#FFFFFF",
            "#9AA3AD",
            "#3D4650",
            "#111111",
    };
    static final String COLOR_DEFAULT = COLORS[0];
    static final String BG_DEFAULT = COLORS[2];

    final JSONObject map;

    DockAppOverrides(JSONObject map) {
        this.map = map != null ? map : new JSONObject();
    }

    static DockAppOverrides load(SharedPreferences prefs) {
        String raw = prefs != null ? prefs.getString(PREF_KEY, "") : "";
        if (raw == null || raw.isEmpty()) return new DockAppOverrides(new JSONObject());
        try {
            return new DockAppOverrides(new JSONObject(raw));
        } catch (JSONException e) {
            Log.w(TAG, "Bad dock_app_overrides JSON", e);
            return new DockAppOverrides(new JSONObject());
        }
    }

    void save(SharedPreferences prefs) {
        if (prefs == null) return;
        prefs.edit().putString(PREF_KEY, map.toString()).apply();
    }

    JSONObject entry(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        return map.optJSONObject(pkg);
    }

    String name(String pkg) {
        JSONObject e = entry(pkg);
        if (e == null) return null;
        String n = e.optString("name", "").trim();
        return n.isEmpty() ? null : n;
    }

    String icon(String pkg) {
        JSONObject e = entry(pkg);
        if (e == null) return null;
        String i = e.optString("icon", "").trim();
        return i.isEmpty() ? null : i;
    }

    String color(String pkg) {
        JSONObject e = entry(pkg);
        if (e == null) return null;
        String c = e.optString("color", "").trim();
        return c.isEmpty() ? null : c;
    }

    String bg(String pkg) {
        JSONObject e = entry(pkg);
        if (e == null) return null;
        String b = e.optString("bg", "").trim();
        return b.isEmpty() ? null : b;
    }

    void put(String pkg, String name, String iconSlug, String colorHex, String bgHex) {
        if (pkg == null || pkg.isEmpty()) return;
        boolean hasName = name != null && !name.trim().isEmpty();
        boolean hasIcon = iconSlug != null && !iconSlug.trim().isEmpty();
        if (!hasName && !hasIcon) {
            map.remove(pkg);
            return;
        }
        try {
            JSONObject e = new JSONObject();
            if (hasName) e.put("name", name.trim());
            if (hasIcon) {
                e.put("icon", iconSlug.trim());
                String c = (colorHex != null && !colorHex.isEmpty()) ? colorHex : COLOR_DEFAULT;
                String b = (bgHex != null && !bgHex.isEmpty()) ? bgHex : BG_DEFAULT;
                e.put("color", c);
                e.put("bg", b);
            }
            map.put(pkg, e);
        } catch (JSONException ignored) {}
    }

    void clear(String pkg) {
        if (pkg != null) map.remove(pkg);
    }

    static boolean isBrandIcon(String slug) {
        return "youtube".equals(slug) || "youtube_music".equals(slug) || "gwm".equals(slug);
    }

    static boolean isDarkPlateSlug(String slug) {
        return "gwm".equals(slug);
    }

    static int parseColor(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Fresh drawable for a substitute slug. Brand assets are returned un-tinted;
     * Material-style vectors get {@code colorHex} applied.
     */
    static Drawable drawableFor(Context ctx, String slug, String colorHex) {
        if (ctx == null || slug == null || slug.isEmpty()) return null;
        Integer resId = resIdForSlug(slug);
        if (resId == null) return null;
        Drawable raw;
        try {
            raw = ctx.getDrawable(resId);
        } catch (Exception e) {
            return null;
        }
        if (raw == null) return null;
        Drawable d = raw.getConstantState() != null
                ? raw.getConstantState().newDrawable().mutate()
                : raw.mutate();
        if (!isBrandIcon(slug)) {
            int color = parseColor(colorHex, Color.WHITE);
            d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        return d;
    }

    static Integer resIdForSlug(String slug) {
        if (slug == null) return null;
        switch (slug) {
            case "youtube": return R.drawable.ic_youtube_default;
            case "youtube_music": return R.drawable.ic_youtube_music_default;
            case "gwm": return R.drawable.ic_gwm;
            case "nav": return R.drawable.ic_sub_nav;
            case "music": return R.drawable.ic_sub_music;
            case "video": return R.drawable.ic_sub_video;
            case "radio": return R.drawable.ic_sub_radio;
            case "settings": return R.drawable.ic_sub_settings;
            case "haval": return R.drawable.ic_sub_haval;
            case "game": return R.drawable.ic_sub_game;
            case "tv": return R.drawable.ic_sub_tv;
            case "phone": return R.drawable.ic_sub_phone;
            case "chat": return R.drawable.ic_sub_chat;
            case "map_alt": return R.drawable.ic_sub_map_alt;
            case "browser": return R.drawable.ic_sub_browser;
            case "camera": return R.drawable.ic_sub_camera;
            case "photos": return R.drawable.ic_sub_photos;
            case "bolt": return R.drawable.ic_sub_bolt;
            case "weather": return R.drawable.ic_sub_weather;
            case "calendar": return R.drawable.ic_sub_calendar;
            case "clock": return R.drawable.ic_sub_clock;
            case "folder": return R.drawable.ic_sub_folder;
            case "download": return R.drawable.ic_sub_download;
            case "store": return R.drawable.ic_sub_store;
            case "star": return R.drawable.ic_sub_star;
            case "mail": return R.drawable.ic_sub_mail;
            case "wifi": return R.drawable.ic_sub_wifi;
            case "speaker": return R.drawable.ic_sub_speaker;
            default: return null;
        }
    }
}
