package com.mapsyncer.network;

public class PayloadContext {

    private final Object platformContext;

    public PayloadContext(Object platformContext) {
        this.platformContext = platformContext;
    }

    public Object getPlatformContext() {
        return platformContext;
    }

    public void enqueueWork(Runnable work) {
        NetworkManager.getHandler().enqueueWork(this, work);
    }

    public Object getPlayer() {
        return NetworkManager.getHandler().getPlayerFromContext(this);
    }
}
