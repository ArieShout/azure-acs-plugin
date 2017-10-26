/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.FilePath;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class IntegrationTest {
    @ClassRule
    public static JenkinsRule r = new JenkinsRule() {
        {
            // otherwise we suffer from the default 180s timeout
            // this can also be override by the system property jenkins.test.timeout if we do not set it here.
            timeout = -1;
        }
    };

    @Rule
    public Timeout globalTimeout = new Timeout(30, TimeUnit.MINUTES);

    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());

    protected TestEnvironment testEnv = null;
    protected AzureCredentials.ServicePrincipal servicePrincipal = null;
    protected Azure azure = null;
    protected KeyPair keyPair = null;
    protected SSHUserPrivateKey sshCredentials = null;

    @Before
    public void setup() throws Exception {
        testEnv = new TestEnvironment();
        LOGGER.log(Level.INFO, "Setting up test environment for {0}, resource group {1}, name {2}",
                new Object[]{testEnv.containerServiceType, testEnv.resourceGroup, testEnv.resourceName});
        servicePrincipal = new AzureCredentials.ServicePrincipal(
                testEnv.subscriptionId,
                testEnv.clientId,
                testEnv.clientSecret,
                testEnv.oauth2TokenEndpoint,
                testEnv.serviceManagementURL,
                testEnv.authenticationEndpoint,
                testEnv.resourceManagerEndpoint,
                testEnv.graphEndpoint);
        azure = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);
        keyPair = new KeyPair();

        LOGGER.log(Level.INFO, "SSH Key used for test container service creation: \npublic key:\n{0}\nprivate key:\n{1}",
                new Object[]{keyPair.publicKey, keyPair.privateKey});

        sshCredentials = mock(SSHUserPrivateKey.class);
        when(sshCredentials.getPrivateKey()).thenReturn(keyPair.privateKey);
        when(sshCredentials.getPrivateKeys()).thenReturn(Collections.singletonList(keyPair.privateKey));
    }

    @After
    public void teardown() throws Exception {
        clearAzureResources();
    }

    protected void clearAzureResources() {
        try {
            azure.resourceGroups().deleteByName(testEnv.resourceGroup);
        } catch (CloudException e) {
            if (e.response().code() != 404) {
                LOGGER.log(Level.SEVERE, null, e);
            }
        }
    }

    public static String generateRandomString(int length) {
        String uuid = UUID.randomUUID().toString();
        return uuid.replaceAll("[^a-z0-9]", "a").substring(0, length);
    }

    public static void loadFile(Class<?> clazz, FilePath workspace, String sourcePath, String destinationPath) throws Exception {
        FilePath dest = new FilePath(workspace, destinationPath);
        dest.getParent().mkdirs();
        try (InputStream in = clazz.getResourceAsStream(sourcePath);
             OutputStream out = dest.write()) {
            IOUtils.copy(in, out);
        }
    }

    protected static class TestEnvironment {
        public final String subscriptionId;
        public final String clientId;
        public final String clientSecret;
        public final String oauth2TokenEndpoint;
        public final String serviceManagementURL;
        public final String authenticationEndpoint;
        public final String resourceManagerEndpoint;
        public final String graphEndpoint;

        public final String location;
        public final String resourceGroup;
        public final String resourceName;
        public final String containerServiceType;

        public final String adminUser = "azureuser";

        public final String dockerUsername;
        public final String dockerPassword;
        public final String dockerRegistry;

        public final List<ResolvedDockerRegistryEndpoint> dockerCredentials;

        protected TestEnvironment() throws MalformedURLException {
            subscriptionId = loadProperty("ACS_TEST_SUBSCRIPTION_ID");
            clientId = loadProperty("ACS_TEST_CLIENT_ID");
            clientSecret = loadProperty("ACS_TEST_CLIENT_SECRET");
            oauth2TokenEndpoint = "https://login.windows.net/" + loadProperty("ACS_TEST_TENANT");

            serviceManagementURL = loadProperty("ACS_TEST_AZURE_MANAGEMENT_URL", "https://management.core.windows.net/");
            authenticationEndpoint = loadProperty("ACS_TEST_AZURE_AUTH_URL", "https://login.microsoftonline.com/");
            resourceManagerEndpoint = loadProperty("ACS_TEST_AZURE_RESOURCE_URL", "https://management.azure.com/");
            graphEndpoint = loadProperty("ACS_TEST_AZURE_GRAPH_URL", "https://graph.windows.net/");

            location = loadProperty("ACS_TEST_LOCATION", "SoutheastAsia");
            resourceGroup = loadProperty("ACS_TEST_RESOURCE_GROUP_PREFIX", "acs-test-" + generateRandomString(10));
            resourceName = loadProperty("ACS_TEST_RESOURCE_NAME_PREFIX", "acs-test-" + generateRandomString(10));
            containerServiceType = loadProperty("ACS_TEST_CONTAINER_TYPE", "Kubernetes");

            dockerUsername = loadProperty("ACS_TEST_DOCKER_USERNAME", "");
            dockerPassword = loadProperty("ACS_TEST_DOCKER_PASSWORD", "");
            dockerRegistry = loadProperty("ACS_TEST_DOCKER_REGISTRY", "");

            dockerCredentials = new ArrayList<>();
            if (StringUtils.isNotBlank(dockerUsername) && StringUtils.isNotEmpty(dockerPassword)) {
                DockerRegistryToken token = new DockerRegistryToken(dockerUsername,
                        Base64.encodeBase64String((dockerUsername + ":" + dockerPassword).getBytes(Charsets.UTF_8)));
                String registry = StringUtils.isBlank(dockerRegistry) ? "https://index.docker.io/v1/" : dockerRegistry;
                dockerCredentials.add(new ResolvedDockerRegistryEndpoint(new URL(registry), token));
            }
        }

        private static String loadProperty(final String name) {
            return loadProperty(name, "");
        }

        private static String loadProperty(final String name, final String defaultValue) {
            final String value = System.getProperty(name);
            if (StringUtils.isEmpty(value)) {
                return loadEnv(name, defaultValue);
            }
            return value;
        }

        private static String loadEnv(final String name, final String defaultValue) {
            String value = System.getenv(name);
            if (StringUtils.isEmpty(value)) {
                return defaultValue;
            }
            return value;
        }
    }

    protected static class KeyPair {
        public String publicKey;
        public String privateKey;

        public KeyPair() throws JSchException {
            com.jcraft.jsch.KeyPair kpair = com.jcraft.jsch.KeyPair.genKeyPair(new JSch(), com.jcraft.jsch.KeyPair.RSA);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            kpair.writePrivateKey(out);
            privateKey = out.toString();
            out.reset();
            kpair.writePublicKey(out, "acs-test");
            publicKey = out.toString();
        }
    }
}
