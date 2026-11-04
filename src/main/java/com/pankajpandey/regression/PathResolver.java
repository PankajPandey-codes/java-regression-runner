/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.pankajpandey.regression;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class PathResolver {
    private static final String RUNNER_DIR = "java-regression-runner";

    private PathResolver() {}

    static Path runnerRoot(Path projectRoot) {
        if (projectRoot == null) return Paths.get(".").toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        Path name = root.getFileName();
        if (name != null && RUNNER_DIR.equals(name.toString())) {
            return root;
        }
        Path candidate = root.resolve(RUNNER_DIR);
        if (Files.isDirectory(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }
        return root;
    }
}
