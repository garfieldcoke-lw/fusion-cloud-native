package com.lucidworks.example.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.nashorn.internal.ir.ObjectNode;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.*;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates how to use JWT authentication to do search queries and indexing against
 * Fusion REST API.
 */
public class ApacheHttpClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApacheHttpClient.class);

    // Object mapper to parse JSON responses from API
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Executor on which we will schedule the task that refreshes the JWT
    // before it expires;
    private static final ScheduledExecutorService refreshTokenExecutor = Executors.newSingleThreadScheduledExecutor();

    // Holds the current JWT.
    // Note that we mark it as volatile because it is accessed by 2 threads.
    // The main thread accesses it to issue queries and
    // a background thread (refreshTokenExecutor) updates it when the JWT needs to be refreshed.
    private static volatile String jwt;

    public static void main(String[] args) throws InterruptedException {
        // Get all of the system properties we use
        String apiUrl = System.getProperty("apiUrl", "http://localhost:6764");
        String user = System.getProperty("user", "admin");
        String password = System.getProperty("password", "password123");
        String intervalMillisString = System.getProperty("intervalMillis", "1000");
        long intervalMillis = Long.parseLong(intervalMillisString);
        String appId = System.getProperty("appId", "datagen");
        String search = System.getProperty("search", "blah+blah");
        String queryUrl = System.getProperty("queryUrl", "/api/apps/" + appId + "/query/" + appId + "?q=" + search);


        // Populate our first JWT.
        // This method re-schedules itself to ensure the JWT is refreshed
        // before it expires.
        refreshJwt(apiUrl, user, password);

        // Endlessly issue queries
        CloseableHttpClient queryClient = HttpClientBuilder.create().disableCookieManagement().build();

        while (true) {
            executeQuery(apiUrl, queryUrl, queryClient);
            Thread.sleep(intervalMillis);
        }


    }

    private static void executeQuery(String apiUrl, String queryUrl, CloseableHttpClient queryClient) {
        String fullUrl = apiUrl + queryUrl;
        HttpGet query = new HttpGet(fullUrl);
        query.addHeader("Authorization", "Bearer " + jwt);

        LOGGER.info("Querying {}", fullUrl);
        try (CloseableHttpResponse response = queryClient.execute(query)) {
            // ensure we got a 2xx (ok) response code
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode > 299) {
                LOGGER.error("Query failed: received non-2xx response {} from" +
                        " Fusion REST API. Exiting...", statusCode);
                //check for an entity and serialize it if there was one to make
                //the error message more informative
                if (response.getEntity() != null) {
                    LOGGER.error("Error response body was: {}", EntityUtils.toString(response.getEntity()));
                }
                System.exit(-1);
            }
        } catch (IOException e) {
            LOGGER.error("Query failed due to exception. Exiting...", e);
            System.exit(-1);
        }
    }

    /**
     * Refreshes the current JWT by obtaining a new one from the Fusion REST API and
     * schedules this method to run again before the JWT expires.
     */
    private static void refreshJwt(String apiUrl, String user, String password) {
        // As required, use HTTP Basic Authentication to retrieve the JWT.
        CredentialsProvider basicAuthProvider = new BasicCredentialsProvider();
        basicAuthProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(user, password)
        );
        CloseableHttpClient basicAuthClient = HttpClientBuilder.create()
                .setDefaultCredentialsProvider(basicAuthProvider)
                .disableCookieManagement()
                .build();
        String loginUrl = apiUrl + "/oauth2/token";
        HttpPost getJWTRequest = new HttpPost(loginUrl);

        // We need this authCache so that we do "Preemptive Basic Authentication" -
        // sending the credentials without waiting to be challenged.
        // The /oauth2/token endpoint doesn't support non-preemptive Basic Authentication.
        HttpHost targetHost = HttpHost.create(apiUrl);
        AuthCache authCache = new BasicAuthCache();
        authCache.put(targetHost, new BasicScheme());
        final HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(basicAuthProvider);
        context.setAuthCache(authCache);

        // Execute the HttpPost to get the JWT
        LOGGER.info("Obtaining new JWT via {}", loginUrl);
        try (CloseableHttpResponse response = basicAuthClient.execute(getJWTRequest, context)) {

            // ensure we got a 2xx (ok) response code
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode > 299) {
                LOGGER.error("Attempt to retrieve JWT token failed: received non-2xx response {} from" +
                        " Fusion REST API. Exiting...", statusCode);
                //check for an entity and serialize it if there was one to make
                //the error message more informative
                if (response.getEntity() != null) {
                    LOGGER.error("Error response body was: {}", EntityUtils.toString(response.getEntity()));
                }
                System.exit(-1);
            }

            // response code was okay, retrieve the JWT from the body
            JsonNode responseJSON = objectMapper.readTree(response.getEntity().getContent());
            jwt = responseJSON.get("access_token").asText();
            long secondsUntilExpiration = responseJSON.get("expires_in").longValue();

            // Reschedule before it expires.
            // graceSeconds determines how early we refresh it (before the actual expiration).
            // We want it to be just early enough that there's no situation where it expires before we refresh it,
            // but no earlier - we want to avoid putting needless strain on Fusion.
            // We use a short grace period if expiration is very soon.
            long graceSeconds = secondsUntilExpiration > 15L ? 10L : 2L;
            long secondsUntilRefresh = secondsUntilExpiration - graceSeconds;
            LOGGER.info("Successfully refreshed JWT, refreshing again in {} seconds", secondsUntilRefresh);

            // schedule it to be refreshed (by calling this method again) before it expires
            refreshTokenExecutor.schedule(() -> refreshJwt(apiUrl, user, password), secondsUntilRefresh, TimeUnit.SECONDS);

        } catch (IOException e) {
            LOGGER.error("Attempt to retrieve JWT token failed due to exception. Exiting...", e);
            System.exit(-1);
        }
    }
}
