package com.havalh6.viewer;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CarPlayUiRequestTest {
    private static class Service implements CarPlayUiRequest.Service {
        Integer status;
        boolean sent = true;
        Runnable duringRead;
        final List<String> calls = new ArrayList<>();

        @Override public Integer readInt(int transaction) {
            calls.add("read:" + transaction);
            if (duringRead != null) duringRead.run();
            return status;
        }

        @Override public boolean writeInt(int transaction, int value) {
            calls.add("write:" + transaction + ":" + value);
            return sent;
        }
    }

    @Test public void activatedPhoneRequestsOnlyNormalUiOnce() {
        CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
        Service service = new Service();
        service.status = 2;
        assertEquals(CarPlayUiRequest.Result.SENT, request.attempt(service));
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(Arrays.asList("read:29", "write:20:0"), service.calls);
    }

    @Test public void disconnectedAndUnknownStatesNeverRequestUi() {
        for (int status : new int[]{-1, 0, 1, 3, 7, 8, 99}) {
            CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
            Service service = new Service();
            service.status = status;
            assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
            service.status = 2;
            assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
            assertEquals(Arrays.asList("read:29"), service.calls);
        }
    }

    @Test public void missingBinderRetriesOnlyWithinTheBound() {
        CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
        Service service = new Service();
        for (int i = 0; i < 5; i++) {
            assertEquals(CarPlayUiRequest.Result.RETRY, request.attempt(service));
        }
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        service.status = 2;
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(Arrays.asList("read:29", "read:29", "read:29",
                "read:29", "read:29", "read:29"), service.calls);
    }

    @Test public void asynchronousBindCanSucceedBeforeTheLastAttempt() {
        CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
        Service service = new Service();
        for (int i = 0; i < 5; i++) request.attempt(service);
        service.status = 2;
        assertEquals(CarPlayUiRequest.Result.SENT, request.attempt(service));
        assertEquals(Arrays.asList("read:29", "read:29", "read:29",
                "read:29", "read:29", "read:29", "write:20:0"), service.calls);
    }

    @Test public void refusedUiRequestDoesNotRepeatTheCommand() {
        CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
        Service service = new Service();
        service.status = 2;
        service.sent = false;
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(Arrays.asList("read:29", "write:20:0"), service.calls);
    }

    @Test public void deadlinePreventsLateBinderOrDelayedWorkerFromShowingUi() {
        final long[] now = {0};
        CarPlayUiRequest request = new CarPlayUiRequest(() -> now[0]);
        Service service = new Service();
        service.status = 2;
        now[0] = 1000;
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(0, service.calls.size());

        now[0] = 0;
        request = new CarPlayUiRequest(() -> now[0]);
        service.duringRead = () -> now[0] = 1000;
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(Arrays.asList("read:29"), service.calls);
    }

    @Test public void cancellationBeforeAttemptDoesNotTouchService() {
        CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
        Service service = new Service();
        service.status = 2;
        request.cancel();
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(0, service.calls.size());
    }

    @Test public void cancellationDuringStatusReadDoesNotShowUi() {
        CarPlayUiRequest request = new CarPlayUiRequest(() -> 0);
        Service service = new Service();
        service.status = 2;
        service.duringRead = request::cancel;
        assertEquals(CarPlayUiRequest.Result.UNAVAILABLE, request.attempt(service));
        assertEquals(Arrays.asList("read:29"), service.calls);
    }
}
