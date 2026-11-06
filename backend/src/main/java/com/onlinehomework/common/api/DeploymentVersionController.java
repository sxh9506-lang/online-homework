package com.onlinehomework.common.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
public class DeploymentVersionController {
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");
    private final String commitSha;

    public DeploymentVersionController(@Value("${RENDER_GIT_COMMIT:unknown}") String commitSha) {
        this.commitSha = commitSha != null && COMMIT_SHA.matcher(commitSha).matches() ? commitSha : "unknown";
    }

    @GetMapping(value = "/internal/deployment-version", produces = MediaType.APPLICATION_JSON_VALUE)
    public VersionResponse version() {
        return new VersionResponse(commitSha);
    }

    public record VersionResponse(String commit) { }
}
