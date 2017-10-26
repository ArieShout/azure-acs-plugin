/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.microsoft.jenkins.acs.integration.TestHelpers.loadProperty;

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

    @Before
    public void setup() throws Exception {
        testEnv = new TestEnvironment();
    }

    protected static class TestEnvironment {
        public final String dockerRegistry;
        public final String dockerUsername;
        public final String dockerPassword;
        public final String dockerRepository;

        public final List<ResolvedDockerRegistryEndpoint> dockerCredentials;

        protected TestEnvironment() throws MalformedURLException {
            // this will be used for the private docker image pulling test
            // apart from the settings here, we need to prepare an image on the repository first
            // RUN:
            //     docker login $ACS_TEST_DOCKER_REGISTRY
            //     docker pull nginx
            //     docker tag nginx:latest $ACS_TEST_DOCKER_REPOSITORY/acs-test-nginx
            //     docker push $ACS_TEST_DOCKER_REPOSITORY/acs-test-nginx
            dockerRegistry = loadProperty("ACS_TEST_DOCKER_REGISTRY", "");
            dockerUsername = loadProperty("ACS_TEST_DOCKER_USERNAME", "");
            dockerPassword = loadProperty("ACS_TEST_DOCKER_PASSWORD", "");
            dockerRepository = loadProperty("ACS_TEST_DOCKER_REPOSITORY", "");

            dockerCredentials = new ArrayList<>();
            if (StringUtils.isNotBlank(dockerUsername) && StringUtils.isNotEmpty(dockerPassword)) {
                DockerRegistryToken token = new DockerRegistryToken(dockerUsername,
                        Base64.encodeBase64String((dockerUsername + ":" + dockerPassword).getBytes(Charsets.UTF_8)));
                String registry = StringUtils.isBlank(dockerRegistry) ? "https://index.docker.io/v1/" : dockerRegistry;
                dockerCredentials.add(new ResolvedDockerRegistryEndpoint(new URL(registry), token));
            }
        }
    }
}
