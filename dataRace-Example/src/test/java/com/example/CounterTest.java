package com.example;

import org.junit.Test;

public class CounterTest {

    /**
     * Drives Counter from two threads. The assertion is intentionally absent:
     * we want a test that passes reliably, so that any conflicting pair TSVD4J
     * reports comes from the analysis and not from a test failure.
     */
    @Test
    public void testConcurrentIncrement() throws Exception {
        final Counter counter = new Counter();

        Thread writerOne = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 500; i++) {
                    counter.increment();
                    counter.record("a" + i);
                }
            }
        });

        Thread writerTwo = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 500; i++) {
                    counter.increment();
                    counter.record("b" + i);
                }
            }
        });

        writerOne.start();
        writerTwo.start();
        writerOne.join();
        writerTwo.join();

        System.out.println("count=" + counter.getCount() + " size=" + counter.size());
    }
}
