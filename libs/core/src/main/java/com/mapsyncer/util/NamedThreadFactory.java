package com.mapsyncer.util;

import java.util.concurrent.atomic.AtomicInteger;

public final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final String baseName;
    private final boolean daemon;

    public NamedThreadFactory(String baseName) {
        this(baseName, false);
    }

    public NamedThreadFactory(String baseName, boolean daemon) {
        this.baseName = baseName;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, baseName + "-" + counter.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    public int getCreatedCount() {
        return counter.get();
    }
}
