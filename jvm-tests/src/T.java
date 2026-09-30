package tests;

import java.util.ArrayList;
import java.util.List;

/** Tiny assertion helper (no JUnit so the project needs no test dependency). */
final class T {
    static int checks = 0, failures = 0;
    static final List<String> failed = new ArrayList<String>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) { failures++; if (failed.size() < 60) failed.add(what); System.out.println("  FAIL: " + what); }
    }

    static void eq(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
              what + " (expected " + expected + ", got " + actual + ")");
    }

    static void section(String s) { System.out.println("\n== " + s); }
}
