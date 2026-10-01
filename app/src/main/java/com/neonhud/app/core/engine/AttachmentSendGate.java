package com.neonhud.app.core.engine;

/**
 * Small UI-independent gate for attachment validation. While one or more attachment checks are in flight,
 * the composer must not accept a send. A counter is used so a future picker/provider path may safely overlap
 * validations without an early reset reopening the send button.
 */
public final class AttachmentSendGate {
    private int validationDepth;

    public synchronized void beginValidation() {
        if (validationDepth < Integer.MAX_VALUE) validationDepth++;
    }

    public synchronized void endValidation() {
        if (validationDepth > 0) validationDepth--;
    }

    public synchronized boolean isBusy() {
        return validationDepth > 0;
    }

    public synchronized boolean canSend(boolean generating, boolean hasText, boolean hasPending, boolean handlerReady) {
        return !generating && !isBusy() && handlerReady && (hasText || hasPending);
    }
}
