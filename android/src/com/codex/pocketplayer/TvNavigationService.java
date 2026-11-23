package com.codex.pocketplayer;

import android.accessibilityservice.AccessibilityService;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class TvNavigationService extends AccessibilityService {
    static final String ENABLED = "tv-navigation-enabled";
    static volatile TvNavigationService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    boolean press(String key) {
        if (!Pairing.prefs(this).getBoolean(ENABLED, false)) return false;
        if ("back".equals(key)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(key)) return performGlobalAction(GLOBAL_ACTION_HOME);
        final int direction;
        switch (key) {
            case "up": direction = View.FOCUS_UP; break;
            case "down": direction = View.FOCUS_DOWN; break;
            case "left": direction = View.FOCUS_LEFT; break;
            case "right": direction = View.FOCUS_RIGHT; break;
            case "select": direction = 0; break;
            default: return false;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return direction != 0 && focusFirst(root, new int[]{64});
            try {
                if (direction == 0) return click(focused);
                AccessibilityNodeInfo to = focused.focusSearch(direction);
                if (to == null) return false;
                try { return to.isVisibleToUser() && to.performAction(AccessibilityNodeInfo.ACTION_FOCUS); }
                finally { to.recycle(); }
            } finally { focused.recycle(); }
        } finally { root.recycle(); }
    }

    private static boolean click(AccessibilityNodeInfo node) {
        if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return false;
        try { return parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
        finally { parent.recycle(); }
    }

    private static boolean focusFirst(AccessibilityNodeInfo node, int[] budget) {
        if (--budget[0] < 0) return false;
        if (node.isVisibleToUser() && node.isFocusable() && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true;
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (focusFirst(child, budget)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }
}
