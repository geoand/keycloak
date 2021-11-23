/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.it.junit5.extension;

import static org.keycloak.it.junit5.extension.CLIDistTest.ReInstall.BEFORE_ALL;
import static org.keycloak.quarkus.runtime.Environment.forceTestLaunchMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.keycloak.it.utils.KeycloakDistribution;
import org.keycloak.quarkus.runtime.Environment;
import org.keycloak.quarkus.runtime.cli.command.Start;
import org.keycloak.quarkus.runtime.cli.command.StartDev;

import io.quarkus.test.junit.QuarkusMainTestExtension;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;

public class CLITestExtension extends QuarkusMainTestExtension {

    private KeycloakDistribution dist;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (getDistributionConfig(context) != null) {
            Launch launch = context.getRequiredTestMethod().getAnnotation(Launch.class);

            if (launch != null) {
                if (dist == null) {
                    dist = new KeycloakDistribution();
                }
                dist.start(Arrays.asList(launch.value()));
            }
        } else {
            configureProfile(context);
            super.beforeEach(context);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        CLIDistTest distributionConfig = getDistributionConfig(context);

        if (distributionConfig != null) {
            if (BEFORE_ALL.equals(distributionConfig.reInstall())) {
                dist = new KeycloakDistribution();
            }
        } else {
            forceTestLaunchMode();
        }

        super.beforeAll(context);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();

        if (type == LaunchResult.class) {
            List<String> outputStream;
            List<String> errStream;
            int exitCode;

            boolean isDistribution = getDistributionConfig(context) != null;

            if (isDistribution) {
                outputStream = dist.getOutputStream();
                errStream = dist.getErrorStream();
                exitCode = dist.getExitCode();
            } else {
                LaunchResult result = (LaunchResult) super.resolveParameter(parameterContext, context);
                outputStream = result.getOutputStream();
                errStream = result.getErrorStream();
                exitCode = result.exitCode();
            }

            return CLIResult.create(outputStream, errStream, exitCode, isDistribution);
        }

        // for now, not support for manual launching using QuarkusMainLauncher
        throw new RuntimeException("Parameter type [" + type + "] not supported");
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == LaunchResult.class;
    }

    private void configureProfile(ExtensionContext context) {
        List<String> cliArgs = getCliArgs(context);

        // when running tests, build steps happen before executing our CLI so that profiles are not set and not taken
        // into account when executing the build steps
        // this is basically reproducing the behavior when using kc.sh
        if (cliArgs.contains(Start.NAME)) {
            Environment.setProfile("prod");
        } else if (cliArgs.contains(StartDev.NAME)) {
            Environment.forceDevProfile();
        }
    }

    private List<String> getCliArgs(ExtensionContext context) {
        Launch annotation = context.getRequiredTestMethod().getAnnotation(Launch.class);

        if (annotation != null) {
            return Arrays.asList(annotation.value());
        }

        return Collections.emptyList();
    }

    private CLIDistTest getDistributionConfig(ExtensionContext context) {
        return context.getTestClass().get().getDeclaredAnnotation(CLIDistTest.class);
    }
}
