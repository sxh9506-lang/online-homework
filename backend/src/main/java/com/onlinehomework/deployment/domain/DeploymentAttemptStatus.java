package com.onlinehomework.deployment.domain;

public enum DeploymentAttemptStatus {
    CLAIMED,
    REQUESTING,
    REQUESTED,
    SUCCEEDED,
    SAFE_FAILURE,
    UNKNOWN
}
