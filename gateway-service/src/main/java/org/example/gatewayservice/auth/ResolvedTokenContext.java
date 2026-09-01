package org.example.gatewayservice.auth;

public final class ResolvedTokenContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ResolvedTokenContext() {
    }

    public static void set(String token) {
        HOLDER.set(token);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

}
