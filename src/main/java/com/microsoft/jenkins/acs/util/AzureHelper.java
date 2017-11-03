/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.util;

import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.acs.AzureACSPlugin;
import com.microsoft.jenkins.acs.Messages;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

/**
 * Helper methods on the Azure related constructs.
 */
public final class AzureHelper {

    public static Azure buildClientFromServicePrincipal(AzureCredentials.ServicePrincipal servicePrincipal) {
        AzureTokenCredentials credentials = new ApplicationTokenCredentials(
                servicePrincipal.getClientId(),
                servicePrincipal.getTenant(),
                servicePrincipal.getClientSecret(),
                servicePrincipal.getAzureEnvironment());
        return Azure
                .configure()
                .withUserAgent(getUserAgent())
                .withInterceptor(new AzureACSPlugin.AzureTelemetryInterceptor())
                .authenticate(credentials)
                .withSubscription(servicePrincipal.getSubscriptionId());
    }

    public static Azure buildClientFromCredentialsId(String azureCredentialsId) {
        AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
        if (StringUtils.isEmpty(servicePrincipal.getClientId())) {
            throw new IllegalArgumentException(Messages.AzureHelper_servicePrincipalNotFound(azureCredentialsId));
        }

        return buildClientFromServicePrincipal(servicePrincipal);
    }

    private static String getUserAgent() {
        String version = "local";
        String instanceId = "local";
        try {
            version = AzureHelper.class.getPackage().getImplementationVersion();
            instanceId = Jenkins.getActiveInstance().getLegacyInstanceId();
        } catch (Exception e) {
        }

        return Constants.PLUGIN_NAME + "/" + version + "/" + instanceId;
    }

    private AzureHelper() {
        // hide constructor
    }
}
