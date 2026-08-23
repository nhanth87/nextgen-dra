package et.elisa.dra.lab.testapp;

import java.util.concurrent.atomic.AtomicLong;

public final class SubscriberState {

    private final String imsi;
    private volatile String msisdnField;
    private final AtomicLong lastEapAuthSuccess = new AtomicLong(0L);

    private volatile boolean attached = true;
    private volatile boolean barred = false;
    private volatile int authVectorsAvailable = 1;
    private volatile String subscribedRat = "EUTRAN";

    public SubscriberState(String imsi, String msisdn) {
        this.imsi = imsi;
        this.msisdnField = msisdn;
    }

    public String imsi() {
        return imsi;
    }

    public String msisdn() {
        return msisdnField;
    }

    void rebindMsisdn(String newMsisdn) {
        msisdnField = newMsisdn;
    }

    public boolean attached() {
        return attached;
    }

    public void setAttached(boolean attached) {
        this.attached = attached;
    }

    public boolean barred() {
        return barred;
    }

    public void setBarred(boolean barred) {
        this.barred = barred;
    }

    public int authVectorsAvailable() {
        return authVectorsAvailable;
    }

    public void setAuthVectorsAvailable(int authVectorsAvailable) {
        this.authVectorsAvailable = Math.max(0, authVectorsAvailable);
    }

    public String subscribedRat() {
        return subscribedRat;
    }

    public void setSubscribedRat(String subscribedRat) {
        this.subscribedRat = subscribedRat == null || subscribedRat.isBlank()
                ? "EUTRAN" : subscribedRat.trim().toUpperCase();
    }

    public long lastEapAuthSuccess() {
        return lastEapAuthSuccess.get();
    }

    public void markEapAuthSuccess(long epochMs) {
        lastEapAuthSuccess.set(epochMs);
    }

    public void resetDefaults() {
        attached = true;
        barred = false;
        authVectorsAvailable = 1;
        subscribedRat = "EUTRAN";
        lastEapAuthSuccess.set(0L);
    }
}
