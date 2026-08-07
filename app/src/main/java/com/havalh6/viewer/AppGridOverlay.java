package com.havalh6.viewer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppGridOverlay {

    // Set of system/internal/vehicle utility packages to filter out from the launcher overlay
    public static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.beantechs.PKIMaintain",
            "com.beantechs.adaptertool.client",
            "com.beantechs.sshost.client",
            "com.beantechs.launcher",
            "com.beantechs.applist",
            "com.beantechs.account",
            "com.beantechs.fotaui",
            "com.beantechs.operatorcenter",
            "com.beantechs.personalization",
            "com.beantechs.personalcenter",
            "com.beantechs.drivinganalysisservice",
            "com.autolink.enginmode",
            "com.apical.cj1005",
            "com.nextdoordeveloper.miperf.miperf",
            "com.google.android.car.kitchensink",
            "com.google.android.gms",
            "app.revanced.android.gms",
            "com.android.support.car.lenspicker",
            "moe.shizuku.privileged.api",
            "com.revanced.net.revancedmanager",
            "com.beantechs.hvac",
            "com.beantechs.btphone",
            "com.beantechs.vehiclecenter",
            "com.beantechs.guidance",
            "com.beantechs.settings",
            "com.android.settings",
            "com.android.car.settings",
            "com.android.car.media",
            "com.android.vending",
            "com.beantechs.mediacenter.h5.ui",
            "com.beantechs.mediacenter.h5.core"
    ));

    private static String getAppLabel(PackageManager pm, ResolveInfo info) {
        if (info == null || info.activityInfo == null) return "";
        String pkg = info.activityInfo.packageName;
        if ("com.beantechs.energyassistant".equalsIgnoreCase(pkg)) {
            return "Assistente de Energia";
        }
        CharSequence label = info.loadLabel(pm);
        return (label != null) ? label.toString() : "";
    }

    public static void setup(Context context, FrameLayout container) {
        if (context == null || container == null) return;

        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> queryApps = pm.queryIntentActivities(mainIntent, 0);
        List<ResolveInfo> apps = new ArrayList<>();

        String selfPackage = context.getPackageName();

        if (queryApps != null) {
            for (ResolveInfo info : queryApps) {
                if (info == null || info.activityInfo == null) continue;
                String pkgName = info.activityInfo.packageName;
                // Ignore self package and any packages in the ignore list
                if (pkgName.equals(selfPackage) || IGNORED_PACKAGES.contains(pkgName)) {
                    continue;
                }
                apps.add(info);
            }
        }

        Collections.sort(apps, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                String strA = getAppLabel(pm, a);
                String strB = getAppLabel(pm, b);
                return strA.compareToIgnoreCase(strB);
            }
        });

        // Horizontal Carousel Container (Transparent Floating Dock)
        FrameLayout carouselCard = new FrameLayout(context);
        carouselCard.setBackgroundColor(Color.TRANSPARENT);
        carouselCard.setPadding(10, 8, 10, 8);

        // Horizontal ScrollView with Fading Edges
        HorizontalScrollView scrollView = new HorizontalScrollView(context) {
            @Override
            protected float getLeftFadingEdgeStrength() {
                return 1.0f;
            }
            @Override
            protected float getRightFadingEdgeStrength() {
                return 1.0f;
            }
        };
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setHorizontalFadingEdgeEnabled(true);
        scrollView.setFadingEdgeLength(60);

        LinearLayout itemsContainer = new LinearLayout(context);
        itemsContainer.setOrientation(LinearLayout.HORIZONTAL);
        itemsContainer.setGravity(Gravity.CENTER_VERTICAL);

        int iconSize = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 54, context.getResources().getDisplayMetrics());

        for (final ResolveInfo info : apps) {
            LinearLayout appItem = new LinearLayout(context);
            appItem.setOrientation(LinearLayout.VERTICAL);
            appItem.setGravity(Gravity.CENTER);
            appItem.setPadding(20, 8, 20, 8);
            appItem.setClickable(true);
            appItem.setFocusable(true);

            ImageView iconView = new ImageView(context);
            Drawable iconDrawable = null;
            String pkgName = (info.activityInfo != null && info.activityInfo.packageName != null)
                    ? info.activityInfo.packageName.toLowerCase()
                    : "";

            // Custom icon handling for vehicle apps
            if (pkgName.equals("com.beantechs.energyassistant")) {
                try {
                    iconDrawable = context.getDrawable(R.drawable.ic_energy_assistant);
                } catch (Exception ignored) {}
            } else if ((pkgName.contains("beantechs") || pkgName.contains("autolink") || pkgName.contains("gwm"))
                    && !pkgName.equals("br.com.redesurftank.havalshisuku")) {
                try {
                    iconDrawable = context.getDrawable(R.drawable.ic_gwm);
                } catch (Exception ignored) {}
            }
            if (iconDrawable == null) {
                iconDrawable = info.loadIcon(pm);
            }
            iconView.setImageDrawable(iconDrawable);
            appItem.addView(iconView, new LinearLayout.LayoutParams(iconSize, iconSize));

            TextView labelView = new TextView(context);
            labelView.setText(getAppLabel(pm, info));
            labelView.setTextColor(Color.WHITE);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            labelView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
            labelView.setGravity(Gravity.CENTER);
            labelView.setSingleLine(true);
            labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labelView.setMaxWidth(iconSize + 40);

            // Add text drop shadow for legibility over transparent canvas
            labelView.setShadowLayer(6f, 0f, 2f, Color.parseColor("#E6000000"));

            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = 6;
            appItem.addView(labelView, labelLp);

            appItem.setOnClickListener(v -> {
                Intent launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                if (launchIntent != null) {
                    try {
                        context.startActivity(launchIntent);
                    } catch (Exception e) {
                        android.util.Log.e("AppGridOverlay", "Failed to launch app: " + info.activityInfo.packageName, e);
                    }
                }
            });

            itemsContainer.addView(appItem);
        }

        scrollView.addView(itemsContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        carouselCard.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Position carousel across the bottom of the screen (height expanded to 130dp, bottom margin set to 54dp)
        FrameLayout.LayoutParams carouselLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 130, context.getResources().getDisplayMetrics()));
        carouselLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        carouselLp.leftMargin = 30;
        carouselLp.rightMargin = 30;
        carouselLp.bottomMargin = 54;

        container.addView(carouselCard, carouselLp);
    }
}
