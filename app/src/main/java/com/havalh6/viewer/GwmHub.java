package com.havalh6.viewer;

/**
 * The GWM dock hub: five actions in a card the size of a bottom-left 2×1
 * widget (two columns, the lower row of the 6×2 board). Home opens the
 * BeanTechs app list. Reboot is a Shizuku shell command, not a package.
 */
public final class GwmHub {
    private GwmHub() {}

    public static final String HOME = "com.beantechs.applist";
    public static final String ENERGY = "com.beantechs.energyassistant";
    public static final String SYSTEM = "com.beantechs.settings";
    public static final String CAR = "com.beantechs.vehiclecenter";
    /**
     * Absolute path so the process does not depend on a shell PATH.
     * Shizuku runs it as the same user that started Shizuku.
     */
    public static final String REBOOT_COMMAND = "/system/bin/reboot";

    public static final class Action {
        /** Package to launch, or null for {@link #reboot}. */
        public final String packageName;
        public final String label;
        /** 0 = top row, 1 = bottom row. */
        public final int row;
        public final boolean reboot;

        Action(String packageName, String label, int row, boolean reboot) {
            this.packageName = packageName;
            this.label = label;
            this.row = row;
            this.reboot = reboot;
        }
    }

    /** Window-space rectangle. */
    public static final class Cell {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        public Cell(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    /** Top row is home, energy, reboot. Bottom row is system config, car config. */
    public static Action[] actions() {
        return new Action[] {
            new Action(HOME, "Início", 0, false),
            new Action(ENERGY, "Energia", 0, false),
            new Action(null, "Reiniciar", 0, true),
            new Action(SYSTEM, "Sistema", 1, false),
            new Action(CAR, "Veículo", 1, false),
        };
    }

    /** Installed packages the hub can open. Reboot is not one of them. */
    public static String[] packages() {
        return new String[] { HOME, ENERGY, SYSTEM, CAR };
    }

    /**
     * Bottom-left 2×1 of the 6×2 widget board. {@code board*} and {@code gap}
     * are the same pixel space; the page's grid gap is 8 CSS pixels.
     */
    public static Cell cardRect(int boardL, int boardT, int boardR, int boardB, int gap) {
        int cols = 6;
        int rows = 2;
        int spanW = 2;
        int spanH = 1;
        int col = 0;
        int row = 1;
        int innerW = boardR - boardL - gap * (cols - 1);
        int innerH = boardB - boardT - gap * (rows - 1);
        if (innerW <= 0 || innerH <= 0) return new Cell(boardL, boardT, 0, 0);
        int cellW = innerW / cols;
        int cellH = innerH / rows;
        int left = boardL + col * (cellW + gap);
        int top = boardT + row * (cellH + gap);
        int width = spanW * cellW + (spanW - 1) * gap;
        int height = spanH * cellH;
        return new Cell(left, top, width, height);
    }
}
