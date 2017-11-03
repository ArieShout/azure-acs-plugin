/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.commands;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.ContainerService;
import com.microsoft.azure.management.compute.ContainerServiceOrchestratorTypes;
import com.microsoft.azure.management.compute.ContainerServices;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import org.junit.Before;
import org.junit.Test;

import static com.microsoft.jenkins.acs.commands.GetContainerServiceInfoCommand.TaskResult;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link GetContainerServiceInfoCommand}
 */
public class GetContainerServiceInfoCommandTest {
    private static final String RESOURCE_GROUP_NAME = "resource-group";
    private static final String CONTAINER_SERVICE_NAME = "container-service";
    private static final ContainerServiceOrchestratorTypes ORCHESTRATOR_TYPE =
            ContainerServiceOrchestratorTypes.KUBERNETES;
    private static final String FQDN = "fqdn.test";
    private static final String ROOT_USER = "azureuser";

    private Azure azure;
    private GetContainerServiceInfoCommand command;

    @Before
    public void setup() {
        azure = prepareAzure();
        command = new GetContainerServiceInfoCommand();
    }

    @Test
    public void testGetAcsInfoSuccess() {
        TaskResult result = command.getAcsInfo(azure, RESOURCE_GROUP_NAME, CONTAINER_SERVICE_NAME, ORCHESTRATOR_TYPE, System.out);
        assertEquals(CommandState.Success, result.getCommandState());
        assertEquals(ORCHESTRATOR_TYPE, result.getOrchestratorType());
        assertEquals(FQDN, result.getFqdn());
        assertEquals(ROOT_USER, result.getAdminUsername());
    }

    @Test
    public void testGetAcsInfoContainerServiceNotFound() {
        TaskResult result = command.getAcsInfo(azure, RESOURCE_GROUP_NAME, CONTAINER_SERVICE_NAME + 1, ORCHESTRATOR_TYPE, System.out);
        assertEquals(CommandState.HasError, result.getCommandState());
        assertNull(result.getOrchestratorType());
    }

    @Test
    public void testGetAcsInfoOrchestratorTypeNotMatch() {
        final ContainerServiceOrchestratorTypes configuredType = ContainerServiceOrchestratorTypes.DCOS;
        assertNotEquals(ORCHESTRATOR_TYPE, configuredType);
        TaskResult result = command.getAcsInfo(azure, RESOURCE_GROUP_NAME, CONTAINER_SERVICE_NAME, configuredType, System.out);
        assertEquals(CommandState.HasError, result.getCommandState());
        assertEquals(ORCHESTRATOR_TYPE, result.getOrchestratorType());
        assertNull(result.getFqdn());
    }

    private Azure prepareAzure() {
        Azure azure = mock(Azure.class);

        ContainerServices containerServices = mock(ContainerServices.class);
        when(azure.containerServices()).thenReturn(containerServices);

        ContainerService containerService = mock(ContainerService.class);
        when(containerServices.getByResourceGroup(RESOURCE_GROUP_NAME, CONTAINER_SERVICE_NAME)).thenReturn(containerService);

        when(containerService.orchestratorType()).thenReturn(ORCHESTRATOR_TYPE);
        when(containerService.masterFqdn()).thenReturn(FQDN);
        when(containerService.linuxRootUsername()).thenReturn(ROOT_USER);

        return azure;
    }
}
