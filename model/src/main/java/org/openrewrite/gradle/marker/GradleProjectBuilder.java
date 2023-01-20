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
package org.openrewrite.gradle.marker;

import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.plugins.PluginManager;
import org.gradle.plugin.use.PluginId;
import org.openrewrite.Tree;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.maven.tree.MavenRepository;
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public final class GradleProjectBuilder {

    private GradleProjectBuilder() {
    }

    public static GradleProject gradleProject(Project project) {
        return new GradleProject(Tree.randomId(),
                project.getName(),
                project.getPath(),
                GradleProjectBuilder.pluginDescriptors(project.getPluginManager()),
                project.getRepositories().stream()
                        .filter(MavenArtifactRepository.class::isInstance)
                        .map(MavenArtifactRepository.class::cast)
                        .map(repo -> MavenRepository.builder()
                                .id(repo.getName())
                                .uri(repo.getUrl().toString())
                                .releases(true)
                                .snapshots(true)
                                .build())
                        .collect(toList()),
                GradleProjectBuilder.dependencyConfigurations(project.getConfigurations()));
    }

    public static List<GradlePluginDescriptor> pluginDescriptors(@Nullable PluginManager pluginManager) {
        if (pluginManager instanceof PluginManagerInternal) {
            return pluginDescriptors((PluginManagerInternal) pluginManager);
        }
        return emptyList();
    }

    public static List<GradlePluginDescriptor> pluginDescriptors(PluginManagerInternal pluginManager) {
        return pluginManager.getPluginContainer().stream()
                .map(plugin -> new GradlePluginDescriptor(
                        plugin.getClass().getName(),
                        pluginIdForClass(pluginManager, plugin.getClass())))
                .collect(toList());
    }

    @Nullable
    private static String pluginIdForClass(PluginManagerInternal pluginManager, Class<?> pluginClass) {
        try {
            Method findPluginIdForClass = PluginManagerInternal.class.getMethod("findPluginIdForClass", Class.class);
            //noinspection unchecked
            Optional<PluginId> maybePluginId = (Optional<PluginId>) findPluginIdForClass.invoke(pluginManager, pluginClass);
            return maybePluginId.map(PluginId::getId).orElse(null);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // On old versions of gradle that don't have this method, returning null is fine
        }
        return null;
    }

    private static final Map<GroupArtifact, GroupArtifact> groupArtifactCache = new HashMap<>();

    private static GroupArtifact groupArtifact(org.openrewrite.maven.tree.Dependency dep) {
        //noinspection ConstantConditions
        return groupArtifactCache.computeIfAbsent(new GroupArtifact(dep.getGroupId(), dep.getArtifactId()), it -> it);
    }

    private static GroupArtifact groupArtifact(ResolvedDependency dep) {
        return groupArtifactCache.computeIfAbsent(new GroupArtifact(dep.getModuleGroup(), dep.getModuleName()), it -> it);
    }

    private static final Map<GroupArtifactVersion, GroupArtifactVersion> groupArtifactVersionCache = new HashMap<>();

    private static GroupArtifactVersion groupArtifactVersion(ResolvedDependency dep) {
        return groupArtifactVersionCache.computeIfAbsent(
                new GroupArtifactVersion(dep.getModuleGroup(), dep.getModuleName(), unspecifiedToNull(dep.getModuleVersion())),
                it -> it);
    }

    private static GroupArtifactVersion groupArtifactVersion(Dependency dep) {
        return groupArtifactVersionCache.computeIfAbsent(
                new GroupArtifactVersion(dep.getGroup(), dep.getName(), unspecifiedToNull(dep.getVersion())), it -> it);
    }

    private static final Map<ResolvedGroupArtifactVersion, ResolvedGroupArtifactVersion> resolvedGroupArtifactVersionCache = new HashMap<>();

    private static ResolvedGroupArtifactVersion resolvedGroupArtifactVersion(ResolvedDependency dep) {
        return resolvedGroupArtifactVersionCache.computeIfAbsent(new ResolvedGroupArtifactVersion(
                        null, dep.getModuleGroup(), dep.getModuleName(), dep.getModuleVersion(), null),
                it -> it);
    }

    /**
     * Some Gradle dependency functions will have the String "unspecified" to indicate a missing value.
     * Rewrite's dependency API represents these missing things as "null"
     */
    @Nullable
    private static String unspecifiedToNull(@Nullable String maybeUnspecified) {
        if ("unspecified".equals(maybeUnspecified)) {
            return null;
        }
        return maybeUnspecified;
    }

    private static Map<String, GradleDependencyConfiguration> dependencyConfigurations(ConfigurationContainer configurationContainer) {
        Map<String, GradleDependencyConfiguration> results = new HashMap<>();
        List<Configuration> configurations = new ArrayList<>(configurationContainer);
        for (Configuration conf : configurations) {
            List<org.openrewrite.maven.tree.Dependency> requested = conf.getAllDependencies().stream()
                    .map(dep -> dependency(dep, conf))
                    .collect(Collectors.toList());

            try {
                ResolvedConfiguration resolvedConf;
                if (conf.isCanBeResolved()) {
                    resolvedConf = conf.getResolvedConfiguration();
                } else {
                    // Some common configuration, like "implementation" are not resolvable.
                    // These configurations are extended from by other configurations which are resolvable
                    // But a recipe author will be interested in where the dependency is originally coming from
                    resolvedConf = configurationContainer.create("resolvable" + conf.getName(), it -> it.extendsFrom(conf))
                            .getResolvedConfiguration();
                }
                Map<GroupArtifact, org.openrewrite.maven.tree.Dependency> gaToRequested = requested.stream()
                        .collect(Collectors.toMap(GradleProjectBuilder::groupArtifact, dep -> dep, (a, b) -> a));
                Map<GroupArtifact, ResolvedDependency> gaToResolved = resolvedConf.getFirstLevelModuleDependencies().stream()
                        .collect(Collectors.toMap(GradleProjectBuilder::groupArtifact, dep -> dep, (a, b) -> a));
                List<org.openrewrite.maven.tree.ResolvedDependency> resolved = resolved(gaToRequested, gaToResolved);
                GradleDependencyConfiguration dc = new GradleDependencyConfiguration(conf.getName(), conf.getDescription(),
                        conf.isTransitive(), conf.isCanBeResolved(), emptyList(), requested, resolved);
                results.put(conf.getName(), dc);
            } catch (Exception e) {
                // No need to fail constructing the marker if a configuration cannot be resolved
            }
        }

        // Record the relationships between dependency configurations
        for (Configuration conf : configurations) {
            if (conf.getExtendsFrom().isEmpty()) {
                continue;
            }
            GradleDependencyConfiguration dc = results.get(conf.getName());
            List<GradleDependencyConfiguration> extendsFrom = conf.getExtendsFrom().stream()
                    .map(it -> results.get(it.getName()))
                    .collect(Collectors.toList());
            dc.unsafeSetExtendsFrom(extendsFrom);
        }
        return results;
    }

    private static final Map<org.openrewrite.maven.tree.Dependency, org.openrewrite.maven.tree.Dependency>
            requestedCache = new HashMap<>();

    private static org.openrewrite.maven.tree.Dependency dependency(Dependency dep, Configuration configuration) {
        return requestedCache.computeIfAbsent(
                org.openrewrite.maven.tree.Dependency.builder()
                        .gav(groupArtifactVersion(dep))
                        .type("jar")
                        .scope(configuration.getName())
                        .exclusions(emptyList())
                        .build(),
                (it) -> it);
    }

    private static final Map<org.openrewrite.maven.tree.ResolvedDependency, org.openrewrite.maven.tree.ResolvedDependency>
            resolvedCache = new HashMap<>();

    private static List<org.openrewrite.maven.tree.ResolvedDependency> resolved(
            Map<GroupArtifact, org.openrewrite.maven.tree.Dependency> gaToRequested,
            Map<GroupArtifact, ResolvedDependency> gaToResolved) {
        return gaToResolved.entrySet().stream()
                .map(entry -> {
                    GroupArtifact ga = entry.getKey();
                    ResolvedDependency resolved = entry.getValue();
                    // There may not be a requested entry if a dependency substitution rule took effect
                    // the DependencyHandler has the substitution mapping buried inside it, but not exposed publicly
                    // Possible improvement to dig that out and use it
                    org.openrewrite.maven.tree.Dependency requested = gaToRequested.getOrDefault(ga, dependency(resolved));
                    // Gradle knows which repository it got a dependency from, but haven't been able to find where that info lives
                    return resolvedCache.computeIfAbsent(org.openrewrite.maven.tree.ResolvedDependency.builder()
                                    .gav(resolvedGroupArtifactVersion(resolved))
                                    .requested(requested)
                                    .dependencies(resolved.getChildren().stream()
                                            .map(child -> resolved(child, 1))
                                            .collect(Collectors.toList()))
                                    .licenses(emptyList())
                                    .depth(0)
                                    .build(),
                            it -> it);
                })
                .collect(Collectors.toList());
    }

    /**
     * When there is a resolved dependency that cannot be matched up with a requested dependency, construct a requested
     * dependency corresponding to the exact version which was resolved. This isn't strictly accurate, but there is no
     * obvious way to access the resolution of transitive dependencies to figure out what versions are requested during
     * the resolution process.
     */
    private static org.openrewrite.maven.tree.Dependency dependency(ResolvedDependency dep) {
        return requestedCache.computeIfAbsent(
                org.openrewrite.maven.tree.Dependency.builder()
                        .gav(groupArtifactVersion(dep))
                        .type("jar")
                        .scope(dep.getConfiguration())
                        .exclusions(emptyList())
                        .build(),
                it -> it);
    }

    private static org.openrewrite.maven.tree.ResolvedDependency resolved(ResolvedDependency dep, int depth) {
        return resolvedCache.computeIfAbsent(
                org.openrewrite.maven.tree.ResolvedDependency.builder()
                        .gav(resolvedGroupArtifactVersion(dep))
                        .requested(dependency(dep))
                        .dependencies(dep.getChildren().stream()
                                .map(child -> resolved(child, depth + 1))
                                .collect(Collectors.toList()))
                        .licenses(emptyList())
                        .depth(depth)
                        .build(),
                it -> it);
    }

    public static void clearCaches() {
        requestedCache.clear();
        resolvedCache.clear();
        groupArtifactCache.clear();
        groupArtifactVersionCache.clear();
    }
}
