package com.wbeam.hud;

import org.json.JSONArray;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Fixed-capacity series buffer used by HUD trend charts.
 */
public final class MetricSeriesBuffer implements Iterable<Double> {
    private final int capacity;
    private final ArrayDeque<Double> values = new ArrayDeque<>();

    public MetricSeriesBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void addSample(double value) {
        values.addLast(value);
        while (values.size() > capacity) {
            values.removeFirst();
        }
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public double latest(double fallback) {
        Double tail = values.peekLast();
        return tail == null ? fallback : tail;
    }

    public JSONArray toJsonFinite() {
        JSONArray arr = new JSONArray();
        if (values.isEmpty()) {
            return arr;
        }
        for (Double sample : values) {
            if (sample == null || !Double.isFinite(sample)) {
                continue;
            }
            arr.put(sample);
        }
        return arr;
    }

    @Override
    public Iterator<Double> iterator() {
        return values.iterator();
    }
}
