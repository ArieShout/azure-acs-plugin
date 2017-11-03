package com.microsoft.jenkins.acs.commands;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.jenkins.acs.AzureACSPlugin;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.orchestrators.DeploymentConfig;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.acs.util.DeployHelper;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.remote.SSHClient;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import com.microsoft.jenkins.kubernetes.credentials.ResolvedDockerRegistryEndpoint;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.Item;
import hudson.model.TaskListener;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.List;

import static com.microsoft.jenkins.acs.util.DeployHelper.escapeSingleQuote;

public class SwarmDeploymentCommand
        implements ICommand<SwarmDeploymentCommand.ISwarmDeploymentCommandData>, Serializable {
    private static final long serialVersionUID = 1L;

    public void execute(SwarmDeploymentCommand.ISwarmDeploymentCommandData context) {
        JobContext jobContext = context.getJobContext();
        final FilePath workspace = jobContext.getWorkspace();
        final TaskListener taskListener = jobContext.getTaskListener();
        final EnvVars envVars = context.getEnvVars();
        final String host = context.getMgmtFQDN();
        final SSHUserPrivateKey sshCredentials = context.getSshCredentials();
        final boolean enableSubstitution = context.isEnableConfigSubstitution();
        final boolean swarmRemoveContainerFirst = context.isSwarmRemoveContainersFirst();
        final DeploymentConfig.Factory configFactory = new DeploymentConfig.Factory(context.getConfigFilePaths());
        final ContainerServiceOrchestratorTypes orchestratorType = context.getOrchestratorType();

        try {
            final List<ResolvedDockerRegistryEndpoint> registryCredentials =
                    context.resolvedDockerRegistryEndpoints(jobContext.getRun().getParent());

            CommandState state = workspace.act(new MasterToSlaveCallable<CommandState, Exception>() {
                private static final long serialVersionUID = 1L;

                @Override
                public CommandState call() throws Exception {
                    PrintStream logger = taskListener.getLogger();

                    DeploymentConfig deploymentConfig = configFactory.build(orchestratorType, workspace, envVars);
                    FilePath[] configFiles = deploymentConfig.getConfigFiles();

                    SSHClient client = new SSHClient(host, Constants.SWARM_SSH_PORT, sshCredentials)
                            .withLogger(logger);

                    try (SSHClient connected = client.connect()) {
                        prepareCredentialsForSwarm(connected, registryCredentials, logger);

                        for (FilePath configFile : configFiles) {
                            final String deployedFilename = DeployHelper.generateRandomDeploymentFileName("yml");
                            logger.println(Messages.SwarmDeploymentCommand_copyConfigFileTo(
                                    configFile.getRemote(), connected.getHost(), deployedFilename));

                            connected.copyTo(
                                    DeployHelper.replaceMacro(
                                            configFile.read(), envVars, enableSubstitution),
                                    deployedFilename);

                            final String escapedName = escapeSingleQuote(deployedFilename);

                            if (swarmRemoveContainerFirst) {
                                logger.println(Messages.SwarmDeploymentCommand_removingDockerContainers());
                                try {
                                    connected.execRemote(String.format(
                                            "DOCKER_HOST=:2375 docker-compose -f '%s' down",
                                            escapedName));
                                } catch (SSHClient.ExitStatusException ex) {
                                    // the service was not found
                                    logger.println(ex.getMessage());
                                }
                            }

                            // Note that we have to specify DOCKER_HOST in the command rather than using
                            // `ChannelExec.setEnv` as the latter one sets environment variable through SSH protocol
                            // but the default sshd_config doesn't allow this
                            logger.println(Messages.SwarmDeploymentCommand_updatingDockerContainers());
                            connected.execRemote(String.format(
                                    "DOCKER_HOST=:2375 docker-compose -f '%s' up -d",
                                    escapedName));

                            logger.println(Messages.SwarmDeploymentCommand_removeTempFile(deployedFilename));
                            connected.execRemote(String.format("rm -f -- '%s'", escapedName));
                        }
                    }
                    return CommandState.Success;
                }
            });

            String action = state.isError() ? Constants.AI_DEPLOY_FAILED : Constants.AI_DEPLOYED;
            AzureACSPlugin.sendEventFor(action, Constants.AI_SWARM, jobContext.getRun(),
                    Constants.AI_FQDN, AppInsightsUtils.hash(host));

            context.setCommandState(state);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.logError(e);
            AzureACSPlugin.sendEventFor(Constants.AI_DEPLOY_FAILED, Constants.AI_SWARM, jobContext.getRun(),
                    Constants.AI_FQDN, AppInsightsUtils.hash(host),
                    Constants.AI_MESSAGE, e.getMessage());
        }
    }

    @VisibleForTesting
    static void prepareCredentialsForSwarm(
            SSHClient client,
            List<ResolvedDockerRegistryEndpoint> registryCredentials,
            PrintStream logger) throws Exception {
        for (ResolvedDockerRegistryEndpoint endpoint : registryCredentials) {
            String auth = StringUtils.trimToEmpty(endpoint.getToken().getToken());
            if (StringUtils.isEmpty(auth)) {
                throw new IllegalArgumentException(Messages.SwarmDeploymentConfig_noAuthTokenFor(endpoint));
            }

            String decoded = new String(Base64.decodeBase64(auth), Constants.DEFAULT_CHARSET);
            String[] parts = decoded.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(Messages.SwarmDeploymentConfig_malformedAuthTokenFor(endpoint));
            }

            String username = parts[0];
            String password = parts[1];
            String server = endpoint.getUrl().toString();

            final String command = String.format("docker login -u '%s' -p '%s' '%s'",
                    escapeSingleQuote(username), escapeSingleQuote(password), escapeSingleQuote(server));

            logger.println(Messages.SwarmDeploymentConfig_addCredentialsFor(server));
            client.execRemote(command, false, false);
        }
    }

    public interface ISwarmDeploymentCommandData extends IBaseCommandData {
        String getMgmtFQDN();

        SSHUserPrivateKey getSshCredentials();

        String getConfigFilePaths();

        ContainerServiceOrchestratorTypes getOrchestratorType();

        boolean isEnableConfigSubstitution();

        boolean isSwarmRemoveContainersFirst();

        List<ResolvedDockerRegistryEndpoint> resolvedDockerRegistryEndpoints(Item context) throws IOException;
    }
}
