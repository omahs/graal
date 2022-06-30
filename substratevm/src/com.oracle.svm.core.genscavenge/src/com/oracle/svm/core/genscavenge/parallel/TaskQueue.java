package com.oracle.svm.core.genscavenge.parallel;

import com.oracle.svm.core.locks.VMCondition;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.log.Log;

import java.util.stream.IntStream;

public class TaskQueue {
    private static final int SIZE = 1024; ///handle overflow

    private static class TaskData {
        private Object object;///no need to wrap
    }

    public final Stats stats;

    private final VMMutex mutex;
    private final VMCondition cond;
    private final TaskData[] data;
    private int getIndex;
    private int putIndex;
    private volatile int idleCount;

    public TaskQueue(String name) {
        mutex = new VMMutex(name + "-queue");
        cond = new VMCondition(mutex);
        data = IntStream.range(0, SIZE).mapToObj(n -> new TaskData()).toArray(TaskData[]::new);
        stats = new Stats();
    }

    private int next(int index) {
        return (index + 1) % SIZE;
    }

    private boolean canGet() {
        return getIndex != putIndex;
    }

    private boolean canPut() {
        return next(putIndex) != getIndex;
    }

    public void put(Object object) {
        try {
            mutex.lock();
            while (!canPut()) {
                Log.log().string("PP cannot put task\n");
                cond.block();
            }
            TaskData item = data[putIndex];
            item.object = object;
        } finally {
            putIndex = next(putIndex);
            stats.noteSize(putIndex, getIndex);
            mutex.unlock();
            cond.broadcast();
        }
    }

    public void consume(Consumer consumer) {
        Object obj;
        try {
            mutex.lock();
            idleCount++;
            while (!canGet()) {
                Log.log().string("PP cannot get task\n");
                cond.block();
            }
            TaskData item = data[getIndex];
            obj = item.object;
        } finally {
            getIndex = next(getIndex);
            stats.noteSize(putIndex, getIndex);
            idleCount--;
            mutex.unlock();
            cond.broadcast();
        }
        consumer.accept(obj);
    }

    public void waitUntilIdle(int expectedIdleCount) {
        try {
            mutex.lock();
            while (canGet()) {
                cond.block();
            }
        } finally {
            mutex.unlock();
        }
        while (idleCount < expectedIdleCount);///signal?
    }

    public interface Consumer {
        void accept(Object object); ///j.u.f.Consumer
    }

    public static class Stats {
        private int maxSize;

        public void noteSize(int putIndex, int getIndex) {
            int size = putIndex - getIndex;
            if (size < 0) {
                size += SIZE;
            }
            if (size > maxSize) {
                maxSize = size;
            }
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void reset() {
            maxSize = 0;
        }
    }
}