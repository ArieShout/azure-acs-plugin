/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.google.common.annotations.VisibleForTesting;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.AzureACSPlugin;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.AzureHelper;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.JobContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsUtils;
import hudson.FilePath;
import hudson.model.TaskListener;
import jenkins.security.MasterToSlaveCallable;

import java.io.PrintStream;
import java.io.Serializable;

public class GetContainerServiceInfoCommand
        implements ICommand<GetContainerServiceInfoCommand.IGetContainerServiceInfoCommandData>, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public void execute(IGetContainerServiceInfoCommandData context) {
        JobContext jobContext = context.getJobContext();
        final FilePath workspace = jobContext.getWorkspace();
        final TaskListener taskListener = jobContext.getTaskListener();
        String azureCredentialsId = context.getAzureCredentialsId();
        final AzureCredentials.ServicePrincipal servicePrincipal =
                AzureCredentials.getServicePrincipal(azureCredentialsId);
        final String resourceGroupName = context.getResourceGroupName();
        final String containerServiceName = context.getContainerServiceName();
        final String containerServiceType = context.getContainerServiceType();
        final ContainerServiceOrchestratorTypes configuredType = context.getOrchestratorType();

        final String aiType = AzureACSPlugin.normalizeContainerSerivceType(containerServiceType);

        AzureACSPlugin.sendEventFor(Constants.AI_START_DEPLOY,
                aiType,
                jobContext.getRun(),
                "Subscription", AppInsightsUtils.hash(
                        servicePrincipal == null ? null : servicePrincipal.getSubscriptionId()),
                "ResourceGroup", AppInsightsUtils.hash(resourceGroupName),
                "ContainerServiceName", AppInsightsUtils.hash(containerServiceName));

        if (Constants.AKS.equals(containerServiceType)) {
            context.setCommandState(CommandState.Success);
            return;
        }

        context.logStatus(Messages.GetContainserServiceInfoCommand_getFQDN());
        try {
            TaskResult taskResult = workspace.act(new MasterToSlaveCallable<TaskResult, RuntimeException>() {
                @Override
                public TaskResult call() throws RuntimeException {
                    PrintStream logger = taskListener.getLogger();

                    Azure azureClient = AzureHelper.buildClientFromServicePrincipal(servicePrincipal);
                    return getAcsInfo(azureClient, resourceGroupName, containerServiceName, configuredType, logger);
                }
            });

            context.setCommandState(taskResult.commandState);
            if (taskResult.commandState.isError()) {
                return;
            }

            context.setMgmtFQDN(taskResult.fqdn);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            context.logError(ex);
            AzureACSPlugin.sendEventFor("GetInfoFailure",
                    aiType,
                    jobContext.getRun(),
                    Constants.AI_MESSAGE, ex.getMessage());
        }
    }

    @VisibleForTesting
    TaskResult getAcsInfo(
            Azure azureClient,
            String resourceGroupName,
            String containerServiceName,
            ContainerServiceOrchestratorTypes configuredType,
            PrintStream logger) {
        TaskResult result = new TaskResult();

        ContainerService containerService =
                azureClient
                        .containerServices()
                        .getByResourceGroup(resourceGroupName, containerServiceName);
        if (containerService == null) {
            logger.println(
                    Messages.GetContainserServiceInfoCommand_containerServiceNotFound(
                            containerServiceName, resourceGroupName));
            result.commandState = CommandState.HasError;
            return result;
        }

        ContainerServiceOrchestratorTypes orchestratorType = containerService.orchestratorType();
        logger.println(Messages.GetContainserServiceInfoCommand_orchestratorType(orchestratorType));
        result.orchestratorType = orchestratorType;

        if (configuredType == null || orchestratorType != configuredType) {
            logger.println(Messages.GetContainserServiceInfoCommand_orchestratorTypeNotMatch(
                    containerServiceName, orchestratorType, configuredType));
            result.commandState = CommandState.HasError;
            return result;
        }

        final String fqdn = containerService.masterFqdn();
        logger.println(Messages.GetContainserServiceInfoCommand_fqdn(fqdn));
        result.fqdn = fqdn;

        final String adminUser = containerService.linuxRootUsername();
        logger.println(Messages.GetContainserServiceInfoCommand_adminUser(adminUser));
        result.adminUsername = adminUser;

        result.commandState = CommandState.Success;

        return result;
    }

    @VisibleForTesting
    static class TaskResult implements Serializable {
        private static final long serialVersionUID = 1L;

        private CommandState commandState = CommandState.Unknown;
        private ContainerServiceOrchestratorTypes orchestratorType;
        private String fqdn;
        private String adminUsername;

        public CommandState getCommandState() {
            return commandState;
        }

        public ContainerServiceOrchestratorTypes getOrchestratorType() {
            return orchestratorType;
        }

        public String getFqdn() {
            return fqdn;
        }

        public String getAdminUsername() {
            return adminUsername;
        }
    }

    public interface IGetContainerServiceInfoCommandData extends IBaseCommandData {
        String getAzureCredentialsId();

        String getResourceGroupName();

        String getContainerServiceName();

        void setMgmtFQDN(String mgmtFQDN);

        ContainerServiceOrchestratorTypes getOrchestratorType();

        String getContainerServiceType();
    }
}
