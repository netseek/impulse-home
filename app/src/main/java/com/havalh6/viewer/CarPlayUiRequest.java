package com.havalh6.viewer;

/** One bounded, cancellable request to show an already-linked CarPlay phone. */
final class CarPlayUiRequest {
    static final int GET_LINK_STATUS = 29;
    static final int LINK_ACTIVATED = 2;
    static final long RETRY_MS = 120;
    private static final int REQUEST_UI = 20;
    private static final int LAUNCH_MODE_NORMAL = 0;
    private static final int MAX_ATTEMPTS = 6;
    private static final long MAX_WAIT_MS = 1000;

    interface Clock { long now(); }

    interface Service {
        Integer readInt(int transaction);
        boolean writeInt(int transaction, int value);
    }

    enum Result { RETRY, SENT, UNAVAILABLE }

    private final Clock clock;
    private final long deadline;
    private int attempts;
    private boolean finished;
    private volatile boolean cancelled;

    CarPlayUiRequest(Clock clock) {
        this.clock = clock;
        deadline = clock.now() + MAX_WAIT_MS;
    }

    void cancel() {
        cancelled = true;
    }

    /** Runs on the projection worker; cancellation may come from the UI thread. */
    Result attempt(Service service) {
        if (cancelled || finished || clock.now() >= deadline) return Result.UNAVAILABLE;
        attempts++;
        Integer status = service.readInt(GET_LINK_STATUS);
        // A binder read may finish after another app was tapped or we were hidden.
        if (cancelled || clock.now() >= deadline) return Result.UNAVAILABLE;
        if (status == null && attempts < MAX_ATTEMPTS) return Result.RETRY;
        finished = true;
        if (status == null || status != LINK_ACTIVATED) return Result.UNAVAILABLE;
        return service.writeInt(REQUEST_UI, LAUNCH_MODE_NORMAL)
                ? Result.SENT : Result.UNAVAILABLE;
    }
}
