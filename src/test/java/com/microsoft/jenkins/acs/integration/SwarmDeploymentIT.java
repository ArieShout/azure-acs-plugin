/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.ACSDeploymentBuilder;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import hudson.model.Result;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Collections;

import static com.microsoft.jenkins.acs.integration.TestHelpers.loadFile;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class SwarmDeploymentIT extends IntegrationTest {
    @ClassRule
    public static ACSRule acs = new ACSRule(ContainerServiceOchestratorTypes.SWARM.toString());

    private ACSDeploymentContext deploymentContext;

    @Before
    @Override
    public void setup() throws Exception {
        super.setup();
        setupJenkinsCredentials(acs);

        deploymentContext = new ACSDeploymentContext(
                azureCredentialsId,
                acs.resourceGroup,
                acs.resourceName + " | Swarm",
                sshCredentialsId,
                "swarm/*.yml");
    }

    @Test
    public void testSingleDirectDeployment() throws Exception {
        loadFile(getClass(), workspace, "swarm/direct.yml");

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);

        verify(run, never()).setResult(Result.FAILURE);
    }

    @Test
    public void testPrivateDockerRegistry() throws Exception {
        if (StringUtils.isBlank(testEnv.dockerUsername)) {
            return;
        }

        loadFile(getClass(), workspace, "swarm/private-repository.yml");

        deploymentContext.setEnableConfigSubstitution(true);
        envVars.put("ACS_TEST_DOCKER_REPOSITORY", testEnv.dockerRepository);
        envVars.put("IMAGE_NAME", "acs-test-private");

        DockerRegistryEndpoint endpoint = new DockerRegistryEndpoint(testEnv.dockerRegistry, dockerCredentialsId);
        deploymentContext.setContainerRegistryCredentials(Collections.singletonList(endpoint));

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);

        verify(run, never()).setResult(Result.FAILURE);
    }

    @Test
    public void testRedeployment() throws Exception {
        loadFile(getClass(), workspace, "swarm/redeploy.yml");

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);
        verify(run, never()).setResult(Result.FAILURE);

        // FIXME: EnablePortCommand is not aware of environment substitution.
        //        If we use variable for the port here, we will get exception and break the build.
        loadFile(getClass(), workspace, "swarm/redeploy2.yml");
        deploymentContext = new ACSDeploymentContext(
                azureCredentialsId,
                acs.resourceGroup,
                acs.resourceName + " | Swarm",
                sshCredentialsId,
                "swarm/redeploy2.yml");

        new ACSDeploymentBuilder(deploymentContext).perform(run, workspace, launcher, taskListener);
        verify(run, never()).setResult(Result.FAILURE);
    }
}
