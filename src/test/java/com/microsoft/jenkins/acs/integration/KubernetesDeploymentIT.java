/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.microsoft.azure.management.compute.ContainerServiceOchestratorTypes;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommand;
import com.microsoft.jenkins.acs.commands.KubernetesDeploymentCommandBase;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.kubernetes.KubernetesClientWrapper;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import static com.microsoft.jenkins.acs.integration.TestHelpers.generateRandomString;
import static com.microsoft.jenkins.acs.integration.TestHelpers.loadFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KubernetesDeploymentIT extends IntegrationTest {
    /**
     * The rule to create the resource group and ACS cluster before the test and delete them afterwards.
     * <p>
     * It takes about 10 minutes for the ACS provision. So we would better provision the ACS for each test suite
     * using {@link ClassRule} rather than each test method ({@link org.junit.Rule}).
     */
    @ClassRule
    public static ACSRule acs = new ACSRule(ContainerServiceOchestratorTypes.KUBERNETES.toString());

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    private FilePath workspace = null;
    private KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData commandData;
    private EnvVars envVars = new EnvVars();

    private String secretName = null;

    @Before
    @Override
    public void setup() throws Exception {
        super.setup();

        workspace = new FilePath(tmpFolder.newFolder());

        commandData = mock(KubernetesDeploymentCommandBase.IKubernetesDeploymentCommandData.class);

        JobContext jobContext = mock(JobContext.class);
        when(jobContext.getWorkspace()).thenReturn(workspace);
        TaskListener taskListener = mock(TaskListener.class);
        when(taskListener.getLogger()).thenReturn(System.err);
        when(jobContext.getTaskListener()).thenReturn(taskListener);
        Run run = mock(Run.class);
        when(run.getDisplayName()).thenReturn(acs.resourceName + "-run");
        when(run.getParent()).thenReturn(mock(Job.class));
        when(jobContext.getRun()).thenReturn(run);
        when(commandData.getJobContext()).thenReturn(jobContext);

        when(commandData.getEnvVars()).thenReturn(envVars);
        when(commandData.getMgmtFQDN()).thenReturn(acs.containerService.masterFqdn());
        when(commandData.getContainerServiceType()).thenReturn(ContainerServiceOchestratorTypes.KUBERNETES.toString());
        when(commandData.getSshCredentials()).thenReturn(acs.sshCredentials);
        when(commandData.getSecretName()).thenReturn(secretName = "acs-test-" + generateRandomString(10));
        when(commandData.getSecretNamespace()).thenReturn("default");
        when(commandData.isEnableConfigSubstitution()).thenReturn(false);
        when(commandData.getConfigFilePaths()).thenReturn("k8s/*.yml");
        when(commandData.getOrchestratorType()).thenReturn(ContainerServiceOchestratorTypes.KUBERNETES);

        when(commandData.resolvedDockerRegistryEndpoints(any(Job.class))).thenReturn(testEnv.dockerCredentials);
    }

    @Test
    public void combinedResourceDeployment() throws Exception {
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/combined-resource.yml");

        new KubernetesDeploymentCommand().execute(commandData);

        verify(commandData).setCommandState(CommandState.Success);
        verify(commandData, never()).logError(any(Exception.class));
    }

    @Test
    public void separeteResourceDeployment() throws Exception {
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/separated-deployment.yml");
        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/separated-service.yml");

        new KubernetesDeploymentCommand().execute(commandData);
        verify(commandData).setCommandState(CommandState.Success);
        verify(commandData, never()).logError(any(Exception.class));
    }

    @Test
    public void testVariableSubstitute() throws Exception {
        when(commandData.isEnableConfigSubstitution()).thenReturn(true);
        envVars.put("DEPLOYMENT_NAME", "substitute-vars-deployment");
        envVars.put("LABEL_APP", "substitute-nginx");

        loadFile(KubernetesDeploymentIT.class, workspace, "k8s/substitute.yml");

        new KubernetesDeploymentCommand().execute(commandData);
        verify(commandData).setCommandState(CommandState.Success);
        verify(commandData, never()).logError(any(Exception.class));

        KubernetesClient client = buildKubernetesClient();
        Deployment deployment = client.extensions().deployments().inNamespace("default").withName("substitute-vars-deployment").get();
        Assert.assertNotNull(deployment);
        Assert.assertEquals("substitute-nginx", deployment.getMetadata().getLabels().get("app"));
    }

    @Test
    public void testDockerCredentials() throws Exception {

    }

    private KubernetesClient buildKubernetesClient() throws Exception {
        File config = tmpFolder.newFile();
        SSHClient client = new SSHClient(acs.containerService.masterFqdn(), Constants.KUBERNETES_SSH_PORT, acs.adminUser, null, acs.keyPair.privateKey);
        try (SSHClient connected = client.connect();
             OutputStream os = new FileOutputStream(config)) {
            connected.copyFrom(".kube/config", os);
        }
        return new KubernetesClientWrapper(config.getAbsolutePath()).getClient();
    }
}
