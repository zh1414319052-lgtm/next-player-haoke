package dev.anilbeesetti.nextplayer.updates;

import dev.anilbeesetti.nextplayer.BuildConfig;

/** QA and stable use the real installed package and separate server/state channels. */
public final class UpdateAppIdentity {
    public static final String APPLICATION_ID = "dev.anilbeesetti.nextplayer.haoke";
    private UpdateAppIdentity() { }
    public static String channel(String applicationId) {
        return channel(applicationId, BuildConfig.DEBUG);
    }
    static String channel(String applicationId, boolean debug) {
        if (!APPLICATION_ID.equals(applicationId)) return null;
        return debug ? "qa" : "stable";
    }
}
