/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.google.common.io.Files;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceMasterProfileCount;
import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.azure.management.compute.ContainerServiceVMSizeTypes;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommand;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommandBase;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KubernetesDeploymentIT extends IntegrationTest {
    private static final Logger LOGGER = Logger.getLogger(KubernetesDeploymentIT.class.getName());

    private ContainerService containerService = null;
    private FilePath workspace = null;
    private KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData commandData;
    private EnvVars envVars = new EnvVars();

    private String secretName = null;

    @Before
    @Override
    public void setup() throws Exception {
        super.setup();

        ResourceGroup resourceGroup = azure.resourceGroups()
                .define(testEnv.resourceGroup)
                .withRegion(testEnv.location)
                .create();
        Assert.assertNotNull(resourceGroup);

        containerService = azure.containerServices()
                .define(testEnv.resourceName)
                .withRegion(testEnv.location)
                .withExistingResourceGroup(testEnv.resourceGroup)
                .withKubernetesOrchestration()
                .withServicePrincipal(servicePrincipal.getClientId(), servicePrincipal.getClientSecret())
                .withLinux()
                .withRootUsername(testEnv.adminUser)
                .withSshKey(keyPair.publicKey)
                .withMasterNodeCount(ContainerServiceMasterProfileCount.MIN)
                .withMasterLeafDomainLabel(testEnv.resourceName)
                .defineAgentPool("pool")
                .withVMCount(1)
                .withVMSize(ContainerServiceVMSizeTypes.STANDARD_A1)
                .withLeafDomainLabel(testEnv.resourceName + "agent")
                .attach()
                .create();
        Assert.assertNotNull(containerService);

        File workspaceDir = Files.createTempDir();
        workspaceDir.deleteOnExit();
        workspace = new FilePath(workspaceDir);

        commandData = mock(KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData.class);

        JobContext jobContext = mock(JobContext.class);
        when(jobContext.getWorkspace()).thenReturn(workspace);
        TaskListener taskListener = mock(TaskListener.class);
        when(taskListener.getLogger()).thenReturn(System.out);
        when(jobContext.getTaskListener()).thenReturn(taskListener);
        Run run = mock(Run.class);
        when(run.getDisplayName()).thenReturn(testEnv.resourceName + "-run");
        when(run.getParent()).thenReturn(mock(Job.class));
        when(jobContext.getRun()).thenReturn(run);
        when(commandData.getJobContext()).thenReturn(jobContext);

        when(commandData.getEnvVars()).thenReturn(envVars);
        when(commandData.getMgmtFQDN()).thenReturn(containerService.masterFqdn());
        when(commandData.getContainerServiceType()).thenReturn(ContainerServiceOchestratorTypes.KUBERNETES.toString());
        when(commandData.getSshCredentials()).thenReturn(sshCredentials);
        when(commandData.getSecretName()).thenReturn(secretName = "acs-test-" + generateRandomString(10));
        when(commandData.getSecretNamespace()).thenReturn("default");
        when(commandData.isEnableConfigSubstitution()).thenReturn(true);
        when(commandData.getConfigFilePaths()).thenReturn("k8s.yml");
        when(commandData.getOrchestratorType()).thenReturn(ContainerServiceOchestratorTypes.KUBERNETES);

        when(commandData.resolvedDockerRegistryEndpoints(any(Job.class))).thenReturn(testEnv.dockerCredentials);
    }

    @After
    @Override
    public void teardown() throws Exception {
        super.teardown();
        if (workspace != null) {
            workspace.deleteRecursive();
        }
    }

    @Test
    public void kubernetesDeployment() throws Exception {
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s.yml", "k8s.yml");

        new KubernetesDeploymentCommand().execute(commandData);

        verify(commandData).setCommandState(CommandState.Success);
        verify(commandData, never()).logError(any(Exception.class));
    }
}
