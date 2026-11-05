package com.onlinehomework.common.api;

import java.net.URI;

public record ApiError(
        URI type,
        String title,
        int status,
        String code,
        String detail,
        boolean retryable,
        String instance) {
}
