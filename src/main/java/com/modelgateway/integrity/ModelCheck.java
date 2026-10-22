package com.modelgateway.integrity;

import com.modelgateway.catalog.UpstreamModel;
import com.modelgateway.upstream.UpstreamResult;
import java.util.regex.Pattern;

/**
 * Compares the model an upstream reports with the one we asked for.
 *
 * <p>A weak signal on its own (a relay can lie about this field), which is why the gateway also
 * pins upstreams to allowlisted official hosts and reconciles against vendor bills. Its real value
 * is catching configuration drift and silent vendor-side aliasing early.
 */
public enum ModelCheck {
    MATCH,
    MISMATCH,
    ABSENT,
    /** The upstream re-ran the request on another model by itself (Anthropic server-side fallback). */
    UPSTREAM_FALLBACK;

    /** Dated snapshots such as {@code gpt-x-2026-05-13} or {@code model-20260513} count as the same model. */
    private static final String DATED_SUFFIX = "(-\\d{4}-?\\d{2}-?\\d{2})?";

    public static ModelCheck of(UpstreamModel expected, UpstreamResult result) {
        if (result.upstreamFallback()) {
            return UPSTREAM_FALLBACK;
        }
        String served = result.servedModel();
        if (served == null || served.isBlank()) {
            return ABSENT;
        }
        if (matches(expected.model(), served)) {
            return MATCH;
        }
        for (String alias : expected.servedModelAliases()) {
            if (matches(alias, served)) {
                return MATCH;
            }
        }
        return MISMATCH;
    }

    private static boolean matches(String name, String served) {
        return Pattern.matches(Pattern.quote(name) + DATED_SUFFIX, served);
    }
}
