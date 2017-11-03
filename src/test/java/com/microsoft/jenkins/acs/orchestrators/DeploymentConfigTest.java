/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.orchestrators;

import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import hudson.EnvVars;
import hudson.FilePath;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DeploymentConfig}.
 */
public class DeploymentConfigTest {
    @Test
    public void testFactoryBuildEmpty() throws Exception {
        FilePath workspace = mock(FilePath.class);
        when(workspace.list(any(String.class))).thenReturn(new FilePath[0]);

        try {
            new DeploymentConfig.Factory("some-path").build(ContainerServiceOrchestratorTypes.KUBERNETES, workspace, new EnvVars());
            fail("Should fail if no config file was found");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }

    @Test
    public void testFactoryBuildOnType() throws Exception {
        FilePath workspace = mock(FilePath.class);
        FilePath[] configFiles = new FilePath[]{mock(FilePath.class)};
        when(workspace.list(any(String.class))).thenReturn(configFiles);

        DeploymentConfig.Factory factory = new DeploymentConfig.Factory("some-path");
        DeploymentConfig dcos = factory.build(ContainerServiceOrchestratorTypes.DCOS, workspace, new EnvVars());
        assertTrue(dcos instanceof MarathonDeploymentConfig);
        DeploymentConfig k8s = factory.build(ContainerServiceOrchestratorTypes.KUBERNETES, workspace, new EnvVars());
        assertTrue(k8s instanceof KubernetesDeploymentConfig);
        DeploymentConfig swarm = factory.build(ContainerServiceOrchestratorTypes.SWARM, workspace, new EnvVars());
        assertTrue(swarm instanceof SwarmDeploymentConfig);

        try {
            factory.build(ContainerServiceOrchestratorTypes.CUSTOM, workspace, new EnvVars());
            fail("Should fail on unsupported CUSTOM orchestrator type");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }
}
