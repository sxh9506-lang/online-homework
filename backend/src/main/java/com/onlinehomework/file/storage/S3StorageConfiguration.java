package com.onlinehomework.file.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true", matchIfMissing = true)
public class S3StorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${app.storage.region}") String region,
                      @Value("${app.storage.endpoint:}") String endpoint,
                      @Value("${app.storage.access-key:}") String accessKey,
                      @Value("${app.storage.secret-key:}") String secretKey) {
        var builder = S3Client.builder().region(Region.of(region));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(@Value("${app.storage.region}") String region,
                            @Value("${app.storage.endpoint:}") String endpoint,
                            @Value("${app.storage.presign-endpoint:}") String presignEndpoint,
                            @Value("${app.storage.access-key:}") String accessKey,
                            @Value("${app.storage.secret-key:}") String secretKey) {
        var builder = S3Presigner.builder().region(Region.of(region));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        String targetEndpoint = presignEndpoint.isBlank() ? endpoint : presignEndpoint;
        if (!targetEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(targetEndpoint))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
