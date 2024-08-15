/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle.toolingapi;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

public interface Dependency {
    GroupArtifactVersion getGav();

    @Nullable
    String getClassifier();

    @Nullable
    String getType();

    String getScope();

    List<GroupArtifact> getExclusions();

    @Nullable
    String getOptional();

    static org.openrewrite.maven.tree.Dependency toMarkers(org.openrewrite.gradle.toolingapi.Dependency dep) {
        return org.openrewrite.maven.tree.Dependency.builder()
                .gav(new org.openrewrite.maven.tree.GroupArtifactVersion(dep.getGav().getGroupId(),
                        dep.getGav().getArtifactId(), dep.getGav().getVersion()))
                .scope(dep.getScope())
                .type(dep.getType())
                .exclusions(dep.getExclusions().stream()
                        .map(ga -> new org.openrewrite.maven.tree.GroupArtifact(ga.getGroupId(), ga.getArtifactId()))
                        .collect(Collectors.toList()))
                .optional(dep.getOptional())
                .build();
    }
}
