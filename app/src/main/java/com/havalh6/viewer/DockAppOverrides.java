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
 * and accent color. Persisted under {@link #PREF_KEY} in the shell prefs.
 */
final class DockAppOverrides {
    private static final String TAG = "DockAppOverrides";

    static final String GWM_HUB_PKG = "__gwm_hub";
    static final String PREF_KEY = "dock_app_overrides";

    /** Impulse TelasScreen substitute icon ids + labels. */
    static final String[][] SUBSTITUTE_ICONS = {
            {"youtube", "YouTube"},
            {"youtube_music", "YT Music"},
            {"gwm", "GWM"},
            {"nav", "Navegação"},
            {"music", "Música"},
            {"video", "Vídeo"},
            {"settings", "Configurações"},
            {"haval", "Carro"},
            {"game", "Jogo"},
            {"tv", "TV"},
            {"phone", "Telefone"},
            {"chat", "Chat"},
            {"map_alt", "Mapa"},
    };

    /** Impulse “Cor de Destaque” swatches. */
    static final String[] COLORS = {
            "#FFFFFF", "#ECEFF1", "#FF0000", "#FF4B4B",
            "#00FF00", "#0000FF", "#4A9EFF", "#90CAF9",
            "#FFFF00", "#FF00FF", "#00FFFF", "#FFA500",
            "#800080", "#808080",
    };

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

    void put(String pkg, String name, String iconSlug, String colorHex) {
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
                String c = (colorHex != null && !colorHex.isEmpty()) ? colorHex : COLORS[0];
                e.put("color", c);
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
            case "settings": return R.drawable.ic_sub_settings;
            case "haval": return R.drawable.ic_sub_haval;
            case "game": return R.drawable.ic_sub_game;
            case "tv": return R.drawable.ic_sub_tv;
            case "phone": return R.drawable.ic_sub_phone;
            case "chat": return R.drawable.ic_sub_chat;
            case "map_alt": return R.drawable.ic_sub_map_alt;
            default: return null;
        }
    }
}
