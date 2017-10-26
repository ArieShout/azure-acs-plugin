/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.acs.integration;

import com.google.common.io.Files;
import hudson.FilePath;

import java.io.File;

public class Try extends IntegrationTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDir();
        dir.deleteOnExit();
        FilePath workspace = new FilePath(new File("D:\\"));

        loadFile(Try.class, workspace, "k8s.yml", "k8s.yml");
    }
}
