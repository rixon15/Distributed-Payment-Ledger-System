package org.example.authorizationservice.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

import java.net.URI;

@Configuration
public class AwsKmsConfig {

    @Bean
    public KmsClient kmsClient(
            @Value("${aws.region}") String region,
            @Value("${aws.kms.endpoint}") String endpointOverride) {

        boolean useLocalStack = StringUtils.hasText(endpointOverride);

        AwsCredentialsProvider credentialsProvider = useLocalStack
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                : DefaultCredentialsProvider.builder().build();

        KmsClientBuilder builder = KmsClient.builder().region(Region.of(region))
                .credentialsProvider(credentialsProvider);

        if(useLocalStack) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

}
