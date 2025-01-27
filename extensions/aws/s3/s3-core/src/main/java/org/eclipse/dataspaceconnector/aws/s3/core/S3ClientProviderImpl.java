package org.eclipse.dataspaceconnector.aws.s3.core;

import org.eclipse.dataspaceconnector.spi.EdcException;
import org.eclipse.dataspaceconnector.spi.types.domain.transfer.SecretToken;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class S3ClientProviderImpl implements S3ClientProvider {
    @Override
    public S3Client provide(String region, SecretToken token) {
        if (token instanceof AwsTemporarySecretToken) {
            var temporary = (AwsTemporarySecretToken) token;
            var credentials = AwsSessionCredentials.create(temporary.getAccessKeyId(), temporary.getSecretAccessKey(), temporary.getSessionToken());
            var credentialsProvider = StaticCredentialsProvider.create(credentials);
            return S3Client.builder()
                    .credentialsProvider(credentialsProvider)
                    .region(Region.of(region))
                    .build();
        } else if (token instanceof AwsSecretToken) {
            var secretToken = (AwsSecretToken) token;
            var credentials = AwsBasicCredentials.create(secretToken.getAccessKeyId(), secretToken.getSecretAccessKey());
            var credentialsProvider = StaticCredentialsProvider.create(credentials);
            return S3Client.builder()
                    .credentialsProvider(credentialsProvider)
                    .region(Region.of(region))
                    .build();

        } else {
            throw new EdcException(String.format("SecretToken %s is not supported", token.getClass()));
        }
    }
}
