/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.jenkins.acs.ACSDeploymentContext;
import com.microsoft.jenkins.acs.util.Constants;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DeploymentChoiceCommand}.
 */
public class DeploymentChoiceCommandTest {
    @Test
    public void testNullType() {
        DeploymentChoiceCommand.IDeploymentChoiceCommandData context = prepareContext(null);
        new DeploymentChoiceCommand().execute(context);

        verify(context, times(1)).setCommandState(CommandState.HasError);
    }

    @Test
    public void testNotSupportedType() {
        DeploymentChoiceCommand.IDeploymentChoiceCommandData context = prepareContext(
                ContainerServiceOrchestratorTypes.CUSTOM.toString());
        new DeploymentChoiceCommand().execute(context);

        verify(context, times(1)).setCommandState(CommandState.HasError);
    }

    @Test
    public void testKubernetes() {
        testChoiceForType(ContainerServiceOrchestratorTypes.KUBERNETES.toString(), KubernetesDeploymentCommand.class);
    }

    @Test
    public void testDcos() {
        testChoiceForType(ContainerServiceOrchestratorTypes.DCOS.toString(), MarathonDeploymentCommand.class);
    }

    @Test
    public void testSwarm() {
        testChoiceForType(ContainerServiceOrchestratorTypes.SWARM.toString(), SwarmDeploymentCommand.class);
    }

    @Test
    public void testAKS() {
        testChoiceForType(Constants.AKS, AKSDeploymentCommand.class);
    }

    private void testChoiceForType(
            final String containerServiceType,
            final Class clazz) {
        DeploymentChoiceCommand.IDeploymentChoiceCommandData context = prepareContext(
                containerServiceType);
        DeploymentChoiceCommand command = new DeploymentChoiceCommand();
        command.execute(context);

        verify(context, times(1)).setCommandState(CommandState.Success);

        assertEquals(clazz, command.nextCommand());
    }

    private DeploymentChoiceCommand.IDeploymentChoiceCommandData prepareContext(
            final String containerServiceType) {
        final DeploymentChoiceCommand.IDeploymentChoiceCommandData context = mock(ACSDeploymentContext.class);
        doReturn(containerServiceType).when(context).getContainerServiceType();

        doAnswer(new Answer() {
            @Override
            public Object answer(final InvocationOnMock invocationOnMock) throws Throwable {
                context.setCommandState(CommandState.HasError);
                return null;
            }
        }).when(context).logError(any(String.class));

        return context;
    }
}
