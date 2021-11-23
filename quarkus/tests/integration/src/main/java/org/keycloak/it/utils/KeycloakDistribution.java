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

package org.keycloak.it.utils;

import static org.keycloak.quarkus.runtime.Environment.LAUNCH_MODE;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.eclipse.aether.artifact.Artifact;

import io.quarkus.bootstrap.util.ZipUtils;

public final class KeycloakDistribution {

    private Process keycloak;
    private int exitCode = -1;
    private final Path distPath;
    private final List<String> outputStream = new ArrayList<>();
    private final List<String> errorStream = new ArrayList<>();

    public <T> KeycloakDistribution() {
        distPath = prepareDistribution();
    }

    public void start(List<String> arguments) {
        clear();
        stopIfRunning();
        try {
            startServer(arguments);
            readOutput();
        } catch (Exception cause) {
            throw new RuntimeException("Failed to start the server", cause);
        } finally {
            stopIfRunning();
        }
    }

    public List<String> getOutputStream() {
        return outputStream;
    }

    public List<String> getErrorStream() {
        return errorStream;
    }

    public int getExitCode() {
        return exitCode;
    }

    private void stopIfRunning() {
        if (keycloak != null && keycloak.isAlive()) {
            try {
                keycloak.destroy();
                keycloak.waitFor(10, TimeUnit.SECONDS);
                exitCode = keycloak.exitValue();
            } catch (Exception cause) {
                keycloak.destroyForcibly();
                throw new RuntimeException("Failed to stop the server", cause);
            }
        }
    }

    private void clear() {
        outputStream.clear();
        errorStream.clear();
        exitCode = -1;
        keycloak = null;
    }

    private Path prepareDistribution() {
        try {
            Path distRootPath = Paths.get(System.getProperty("java.io.tmpdir")).resolve("kc-tests");
            distRootPath.toFile().mkdirs();
            File distFile = Maven.resolveArtifact("org.keycloak", "keycloak-server-x-dist", "zip")
                    .map(Artifact::getFile)
                    .orElseThrow(new Supplier<RuntimeException>() {
                        @Override
                        public RuntimeException get() {
                            return new RuntimeException("Could not obtain distribution artifact");
                        }
                    });
            String distDirName = distFile.getName().replace("keycloak-server-x-dist", "keycloak.x");
            Path distPath = distRootPath.resolve(distDirName.substring(0, distDirName.lastIndexOf('.')));

            distPath.toFile().delete();
            ZipUtils.unzip(distFile.toPath(), distRootPath);

            // make sure kc.sh is executable
            distPath.resolve("bin").resolve("kc.sh").toFile().setExecutable(true);

            return distPath;
        } catch (Exception cause) {
            throw new RuntimeException("Failed to prepare distribution", cause);
        }
    }

    private void readOutput() {
        try (
                BufferedReader outStream = new BufferedReader(new InputStreamReader(keycloak.getInputStream()));
                BufferedReader errStream = new BufferedReader(new InputStreamReader(keycloak.getErrorStream()));
        ) {
            while (keycloak.isAlive()) {
                readStream(outStream, outputStream);
                readStream(errStream, errorStream);
            }
        } catch (Throwable cause) {
            throw new RuntimeException("Failed to read server output", cause);
        }
    }

    private void readStream(BufferedReader reader, List<String> stream) throws IOException {
        String line;

        while (reader.ready() && (line = reader.readLine()) != null) {
            stream.add(line);
            System.out.println(line);
        }
    }

    private String[] getCliArgs(List<String> arguments) {
        List<String> commands = new ArrayList<>();

        commands.add("./kc.sh");
        commands.add("-D" + LAUNCH_MODE + "=test");
        commands.addAll(arguments);

        return commands.toArray(new String[0]);
    }

    /**
     * The server is configured to redirect errors to output stream. This adds a limitation when checking whether a
     * message arrived via error stream.
     *
     * @param arguments the list of arguments to run the server
     * @throws Exception if something bad happens
     */
    private void startServer(List<String> arguments) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getCliArgs(arguments));
        ProcessBuilder builder = pb.directory(distPath.resolve("bin").toFile());

        builder.environment().put("KEYCLOAK_ADMIN", "admin");
        builder.environment().put("KEYCLOAK_ADMIN_PASSWORD", "admin");

        FileUtils.deleteDirectory(distPath.resolve("data").toFile());

        keycloak = builder.start();
    }
}
