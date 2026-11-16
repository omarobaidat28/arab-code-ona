package com.humanclicker;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simple persistent log (files/log.txt, rotated at 1 MB) that the user can view and share. */
public final class HLog {
    private static File file;
    public static volatile boolean verbose = true;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final long MAX_SIZE = 1024 * 1024;

    public static synchronized void init(Context c) {
        if (file != null) return;
        file = new File(c.getApplicationContext().getFilesDir(), "log.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (!(prev instanceof CrashHandler)) Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(prev));
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        final Thread.UncaughtExceptionHandler prev;

        CrashHandler(Thread.UncaughtExceptionHandler prev) {
            this.prev = prev;
        }

        public void uncaughtException(Thread t, Throwable e) {
            e("CRASH in thread " + t.getName(), e);
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    public static void i(String msg) {
        add("I", msg);
    }

    public static void v(String msg) {
        if (verbose) add("V", msg);
    }

    public static void w(String msg) {
        add("W", msg);
    }

    public static void e(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        add("E", msg + (t != null ? "\n" + sw : ""));
    }

    private static synchronized void add(String level, String msg) {
        String line = FMT.format(new Date()) + " " + level + " [" + Thread.currentThread().getName() + "] " + msg + "\n";
        if (file == null) return;
        try {
            if (file.length() > MAX_SIZE) {
                File old = new File(file.getParentFile(), "log.old.txt");
                old.delete();
                file.renameTo(old);
            }
            FileWriter w = new FileWriter(file, true);
            w.write(line);
            w.close();
        } catch (Exception ignored) {
        }
    }

    /** Last `maxChars` characters of the log. */
    public static synchronized String tail(int maxChars) {
        if (file == null || !file.exists()) return "(السجل فاضي)";
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            long len = raf.length();
            long start = Math.max(0, len - maxChars);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            raf.close();
            String s = new String(b, "UTF-8");
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Exception e) {
            return "تعذر قراءة السجل: " + e;
        }
    }

    public static synchronized void clear() {
        if (file != null) {
            file.delete();
            new File(file.getParentFile(), "log.old.txt").delete();
        }
    }
}
