package com.im.oldchat.models;

import java.util.ArrayList;
import java.util.List;

public class LyricLine {
    private long time;
    private long endTime;
    private String text;
    private List<TimedWord> timedWords;
    private int overlapGroup;

    public LyricLine(long time, String text) {
        this.time = time;
        this.endTime = time;
        this.text = text;
        this.overlapGroup = 0;
    }

    public LyricLine(long time, String text, List<TimedWord> timedWords) {
        this.time = time;
        this.endTime = time;
        this.text = text;
        setTimedWords(timedWords);
    }

    public LyricLine(long time, long endTime, String text, List<TimedWord> timedWords) {
        this.time = time;
        this.endTime = endTime;
        this.text = text;
        setTimedWords(timedWords);
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<TimedWord> getTimedWords() {
        return timedWords;
    }

    public void setTimedWords(List<TimedWord> timedWords) {
        if (timedWords == null || timedWords.isEmpty()) {
            this.timedWords = null;
            return;
        }
        this.timedWords = new ArrayList<TimedWord>(timedWords);
    }

    public boolean hasTimedWords() {
        return timedWords != null && !timedWords.isEmpty();
    }

    public int getOverlapGroup() {
        return overlapGroup;
    }

    public void setOverlapGroup(int overlapGroup) {
        this.overlapGroup = overlapGroup;
    }

    /**
     * 判断某个播放时刻该行是否处于"活动"状态。
     * TTML 有 end 时用 [begin, end] 区间；无 end（LRC）时用 begin 且下一行 begin 作为自然结束。
     * 同时/重叠行（合唱、双声部）由调用方按区间相交判定，这里只提供单行区间。
     */
    public boolean isActiveAt(long timeMs) {
        if (timeMs < time) {
            return false;
        }
        if (endTime > time) {
            return timeMs <= endTime;
        }
        return true;
    }

    /** 该行是否与另一行在时间上重叠（用于 LRC/TTML 同时播放判定）。 */
    public boolean overlaps(LyricLine other, long toleranceMs) {
        if (other == null) {
            return false;
        }
        long aStart = time;
        long aEnd = endTime > time ? endTime : Long.MAX_VALUE;
        long bStart = other.time;
        long bEnd = other.endTime > other.time ? other.endTime : Long.MAX_VALUE;
        long tolerance = Math.max(0L, toleranceMs);
        return aStart < bEnd + tolerance && bStart < aEnd + tolerance;
    }

    public static class TimedWord {
        private String text;
        private long startTime;
        private long endTime;

        public TimedWord(String text, long startTime, long endTime) {
            this.text = text;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public String getText() {
            return text;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }
    }
}
