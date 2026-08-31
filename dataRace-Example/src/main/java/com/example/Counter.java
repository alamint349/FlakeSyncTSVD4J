package com.example;

/**
 * Deliberately unsafe. Two threads read-modify-write the same non-volatile
 * fields with no synchronization, so TSVD4J should report conflicting
 * interception points on 'count' and on the 'items' collection.
 */
public class Counter {

    private int count = 0;

    private java.util.List<String> items = new java.util.ArrayList<String>();

    public void increment() {
        // GETFIELD then PUTFIELD -- the race TSVD4J is looking for.
        this.count = this.count + 1;
    }

    public int getCount() {
        return this.count;
    }

    public void record(String value) {
        // Unsynchronized ArrayList mutation -- the collection-API race.
        this.items.add(value);
    }

    public int size() {
        return this.items.size();
    }
}
