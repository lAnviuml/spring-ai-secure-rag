package dev.anvium.securerag.config;

public final class TenantContext {
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private TenantContext() { }

    public static void set(Identity identity) { CURRENT.set(identity); }

    public static Identity require() {
        Identity identity = CURRENT.get();
        if (identity == null) throw new IllegalStateException("No tenant identity is bound to the request");
        return identity;
    }

    public static void clear() { CURRENT.remove(); }

    public record Identity(String tenantId, String principalId) { }
}
