package com.guichaguri.trackplayer.service.errors;

import android.os.Bundle;

public final class StructuredPlaybackError {
    private final String code;
    private final String message;
    private final String domain;
    private final String reason;
    private final boolean recoverable;

    public StructuredPlaybackError(
            String code,
            String message,
            String domain,
            String reason,
            boolean recoverable) {
        this.code = code;
        this.message = message;
        this.domain = domain;
        this.reason = reason;
        this.recoverable = recoverable;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getDomain() {
        return domain;
    }

    public String getReason() {
        return reason;
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("code", code);
        bundle.putString("message", message);
        bundle.putString("domain", domain);
        bundle.putString("reason", reason);
        bundle.putBoolean("recoverable", recoverable);
        return bundle;
    }
}
