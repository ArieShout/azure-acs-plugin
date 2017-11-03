/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.jenkins.acs.Messages;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.command.INextCommandAware;

/**
 * Makes decision on which deployment method to be used based on the configuration.
 */
public class DeploymentChoiceCommand
        implements ICommand<DeploymentChoiceCommand.IDeploymentChoiceCommandData>, INextCommandAware {
    private String containerServiceType;

    @Override
    public void execute(IDeploymentChoiceCommandData context) {
        String type = context.getContainerServiceType();
        if (type == null) {
            context.logError(Messages.DeploymentChoiceCommand_containerServiceTypeNotFound());
            return;
        }
        if (!Constants.AKS.equals(type) && !Constants.SUPPORTED_ORCHESTRATOR_NAMES.contains(type)) {
            context.logError(Messages.DeploymentChoiceCommand_orchestratorNotSupported(type));
            return;
        }
        this.containerServiceType = type;
        context.setCommandState(CommandState.Success);
    }

    @Override
    public Class nextCommand() {
        if (ContainerServiceOrchestratorTypes.KUBERNETES.toString().equals(containerServiceType)) {
            return KubernetesDeploymentCommand.class;
        } else if (ContainerServiceOrchestratorTypes.DCOS.toString().equals(containerServiceType)) {
            return MarathonDeploymentCommand.class;
        } else if (ContainerServiceOrchestratorTypes.SWARM.toString().equals(containerServiceType)) {
            return SwarmDeploymentCommand.class;
        } else if (Constants.AKS.equals(containerServiceType)) {
            return AKSDeploymentCommand.class;
        }

        throw new IllegalStateException(
                Messages.DeploymentChoiceCommand_orchestratorNotSupported(containerServiceType));
    }

    public interface IDeploymentChoiceCommandData extends IBaseCommandData {
        String getContainerServiceType();
    }
}
