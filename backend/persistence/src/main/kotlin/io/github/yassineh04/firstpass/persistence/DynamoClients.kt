package io.github.yassineh04.firstpass.persistence

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.net.URI

/** Builds the DynamoDB client, with the URLConnection HTTP client that loads faster on Lambda. */
object DynamoClients {
    /**
     * Without [localEndpoint], the region and credentials come from the environment, as Lambda
     * provides them. With it, the client talks to DynamoDB Local, which accepts any region and any
     * credentials: the placeholder values below are not secrets.
     */
    fun create(localEndpoint: URI? = null): DynamoDbClient {
        val builder = DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.create())
        if (localEndpoint != null) {
            builder
                .endpointOverride(localEndpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
        }
        return builder.build()
    }
}
