package com.agentframework.testutil;
import java.util.function.Supplier;
public final class Assert {
    private Assert(){}
    public static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("FAIL: " + msg);
    }
    public static void assertFalse(boolean condition, String msg) {
        assertTrue(!condition, msg);
    }
    public static void assertEquals(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("FAIL: " + msg + " expected=<" + expected + "> actual=<" + actual + ">");
    }
    public static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual)
            throw new AssertionError("FAIL: " + msg + " expected=<" + expected + "> actual=<" + actual + ">");
    }
    public static void assertNotNull(Object obj, String msg) {
        assertTrue(obj != null, msg + " (was null)");
    }
    public static void assertNull(Object obj, String msg) {
        assertTrue(obj == null, msg + " (was " + obj + ")");
    }
    public static void assertContains(String haystack, String needle, String msg) {
        assertTrue(haystack != null && haystack.contains(needle),
            msg + " | expected to contain: " + needle + " in: " + haystack);
    }
    public static <T extends Throwable> T assertThrows(Class<T> type, Runnable r, String msg) {
        try { r.run(); }
        catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("FAIL: " + msg + " wrong exception: " + t, t);
        }
        throw new AssertionError("FAIL: " + msg + " expected " + type.getSimpleName() + " but nothing thrown");
    }
}
