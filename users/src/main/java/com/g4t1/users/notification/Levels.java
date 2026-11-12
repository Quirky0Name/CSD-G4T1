package com.g4t1.users.notification;

import java.util.List;

// The order of alert severity, change severity and impact level: none, low, medium, high
final class Levels {

    private static final List<String> ORDER = List.of("none", "low", "medium", "high");

    private Levels() {
    }

    // a null threshold never passes, and a null level reaches nothing
    static boolean reaches(String level, String threshold) {
        return level != null && threshold != null && rank(level) >= rank(threshold);
    }

    // none, low, medium or high: the values a change severity can take
    static boolean isChangeSeverity(String value) {
        return rank(value) >= 0;
    }

    // low, medium or high: the values a threshold or an impact level can take
    static boolean isLevel(String value) {
        return rank(value) > 0;
    }

    // -1 for null or anything else
    static int rank(String level) {
        return level == null ? -1 : ORDER.indexOf(level);
    }
}
