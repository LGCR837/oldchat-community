package com.im.oldchat.util;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public final class FileSortCompat {
    private FileSortCompat() {
    }

    private static final class Snapshot {
        final File file;
        final long lastModified;
        final String path;
        final int index;

        Snapshot(File file, int index) {
            this.file = file;
            this.index = index;
            if (file == null) {
                this.lastModified = Long.MIN_VALUE;
                this.path = "";
            } else {
                long value;
                try {
                    value = file.lastModified();
                } catch (Exception e) {
                    value = Long.MIN_VALUE + 1;
                }
                this.lastModified = value;
                String p;
                try {
                    p = file.getAbsolutePath();
                } catch (Exception e) {
                    p = "";
                }
                this.path = p == null ? "" : p;
            }
        }
    }

    public static void sortByLastModifiedAsc(File[] files) {
        if (files == null || files.length < 2) {
            return;
        }
        Snapshot[] snapshots = new Snapshot[files.length];
        for (int i = 0; i < files.length; i++) {
            snapshots[i] = new Snapshot(files[i], i);
        }
        Arrays.sort(snapshots, new Comparator<Snapshot>() {
            @Override
            public int compare(Snapshot a, Snapshot b) {
                if (a == b) {
                    return 0;
                }
                if (a == null && b == null) {
                    return 0;
                }
                if (a == null) {
                    return -1;
                }
                if (b == null) {
                    return 1;
                }
                if (a.lastModified < b.lastModified) {
                    return -1;
                }
                if (a.lastModified > b.lastModified) {
                    return 1;
                }
                int pathCmp = a.path.compareTo(b.path);
                if (pathCmp != 0) {
                    return pathCmp;
                }
                if (a.index < b.index) {
                    return -1;
                }
                if (a.index > b.index) {
                    return 1;
                }
                return 0;
            }
        });
        for (int i = 0; i < snapshots.length; i++) {
            files[i] = snapshots[i] == null ? null : snapshots[i].file;
        }
    }
}
