/**
 *  Copyright 2017 Gunnar Morling (http://www.gunnarmorling.de/)
 *  and/or other contributors as indicated by the @authors tag. See the
 *  copyright.txt file in the distribution for a full listing of all
 *  contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.moditect.mavenplugin.generate;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.moditect.commands.GenerateModuleInfo;
import org.moditect.mavenplugin.common.model.ArtifactConfiguration;
import org.moditect.mavenplugin.common.model.ModuleInfoConfiguration;
import org.moditect.mavenplugin.generate.model.ArtifactIdentifier;
import org.moditect.mavenplugin.generate.model.ModuleConfiguration;
import org.moditect.mavenplugin.util.ArtifactResolutionHelper;
import org.moditect.mavenplugin.util.MojoLog;
import org.moditect.model.DependencePattern;
import org.moditect.model.DependencyDescriptor;
import org.moditect.model.PackageNamePattern;

/**
 * @author Gunnar Morling
 */
@Mojo(name = "generate-module-info", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateModuleInfoMojo extends AbstractMojo {

    @Component
    private ArtifactResolver artifactResolver;

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    @Component
    private RepositorySystem repoSystem;

    @Parameter( defaultValue = "${repositorySystemSession}", readonly = true, required = true )
    private RepositorySystemSession repoSession;

    @Parameter( defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true )
    private List<RemoteRepository> remoteRepos;

    @Parameter(readonly = true, defaultValue = "${project.build.directory}/moditect")
    private File workingDirectory;

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/generated-sources/modules")
    private File outputDirectory;

    @Parameter
    private List<ModuleConfiguration> modules;

    @Parameter(property = "moditect.artifact")
    private String artifactOverride;

    @Parameter(property = "moditect.additionalDependencies")
    private String additionalDependenciesOverride;

    @Parameter(property = "moditect.moduleName")
    private String moduleNameOverride;

    @Parameter(property = "moditect.exportExcludes")
    private String exportExcludesOverride;

    @Parameter(property = "moditect.addServiceUses", defaultValue = "false")
    private boolean addServiceUsesOverride;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        createDirectories();

        ArtifactResolutionHelper artifactResolutionHelper = new ArtifactResolutionHelper( repoSystem, repoSession, remoteRepos );
        Map<ArtifactIdentifier, String> assignedNamesByModule = getAssignedModuleNamesByModule( artifactResolutionHelper );

        if ( artifactOverride != null ) {
            processModule( getModuleConfigurationFromOverrides(), assignedNamesByModule, artifactResolutionHelper );
        }
        else {
            for ( ModuleConfiguration moduleConfiguration : modules ) {
                processModule( moduleConfiguration, assignedNamesByModule, artifactResolutionHelper );
            }
        }
    }

    private Map<ArtifactIdentifier, String> getAssignedModuleNamesByModule(ArtifactResolutionHelper artifactResolutionHelper) throws MojoExecutionException {
        Map<ArtifactIdentifier, String> assignedNamesByModule = new HashMap<>();

        for ( ModuleConfiguration configuredModule : modules ) {
            assignedNamesByModule.put(
                new ArtifactIdentifier( artifactResolutionHelper.resolveArtifact( configuredModule.getArtifact() ) ),
                configuredModule.getModuleInfo().getName()
            );
        }

        return assignedNamesByModule;
    }

    private ModuleConfiguration getModuleConfigurationFromOverrides() {
        ModuleConfiguration moduleConfiguration = new ModuleConfiguration();

        moduleConfiguration.setArtifact( new ArtifactConfiguration( artifactOverride ) );
        moduleConfiguration.setModuleInfo( new ModuleInfoConfiguration() );
        moduleConfiguration.getModuleInfo().setName( moduleNameOverride );

        if ( additionalDependenciesOverride != null ) {
            for ( String additionalDependency : additionalDependenciesOverride.split( "\\," ) ) {
                moduleConfiguration.getAdditionalDependencies().add( new ArtifactConfiguration( additionalDependency ) );
            }
        }

        if ( exportExcludesOverride != null ) {
            moduleConfiguration.getModuleInfo().setExports( exportExcludesOverride );
        }

        moduleConfiguration.getModuleInfo().setAddServiceUses( addServiceUsesOverride );

        return moduleConfiguration;
    }

    private void processModule(ModuleConfiguration moduleConfiguration, Map<ArtifactIdentifier, String> assignedNamesByModule, ArtifactResolutionHelper artifactResolutionHelper) throws MojoExecutionException {
        Artifact inputArtifact = artifactResolutionHelper.resolveArtifact( moduleConfiguration.getArtifact() );

        Set<DependencyDescriptor> dependencies = getDependencies( inputArtifact, assignedNamesByModule, artifactResolutionHelper );

        for( ArtifactConfiguration further : moduleConfiguration.getAdditionalDependencies() ) {
            Artifact furtherArtifact = artifactResolutionHelper.resolveArtifact( further );
            dependencies.add( new DependencyDescriptor( furtherArtifact.getFile().toPath(), false, null ) );
        }

        Set<String> uses;

        if ( moduleConfiguration.getModuleInfo().getUses() != null ) {
            uses = Arrays.stream( moduleConfiguration.getModuleInfo().getUses().split( ";" ) )
                .map( String::trim )
                .collect( Collectors.toSet() );
        }
        else {
            uses = Collections.emptySet();
        }

        new GenerateModuleInfo(
                inputArtifact.getFile().toPath(),
                moduleConfiguration.getModuleInfo().getName(),
                dependencies,
                PackageNamePattern.parsePatterns( moduleConfiguration.getModuleInfo().getExports() ),
                DependencePattern.parsePatterns( moduleConfiguration.getModuleInfo().getRequires() ),
                workingDirectory.toPath(),
                outputDirectory.toPath(),
                uses,
                moduleConfiguration.getModuleInfo().isAddServiceUses(),
                new MojoLog( getLog() )
        )
        .run();
    }

    private Set<DependencyDescriptor> getDependencies(Artifact inputArtifact, Map<ArtifactIdentifier, String> assignedNamesByModule, ArtifactResolutionHelper artifactResolutionHelper) throws MojoExecutionException {
        CollectRequest collectRequest = new CollectRequest( new Dependency( inputArtifact, "provided" ), remoteRepos );
        CollectResult collectResult = null;

        try {
            RepositorySystemSession sessionWithProvided = new DefaultRepositorySystemSession( repoSession )
                .setDependencySelector(
                    new AndDependencySelector(
                        new ScopeDependencySelector( "test" ),
                        new OptionalDependencySelector(),
                        new ExclusionDependencySelector()
                    )
                );

            collectResult = repoSystem.collectDependencies( sessionWithProvided, collectRequest );
        }
        catch (DependencyCollectionException e) {
            throw new MojoExecutionException( "Couldn't collect dependencies", e );
        }

        Set<DependencyDescriptor> dependencies = new LinkedHashSet<>();

        for ( DependencyNode dependency : collectResult.getRoot().getChildren() ) {
            Artifact resolvedDependency = artifactResolutionHelper.resolveArtifact( dependency.getDependency().getArtifact() );
            String assignedModuleName = assignedNamesByModule.get( new ArtifactIdentifier( resolvedDependency ) );

            dependencies.add(
                    new DependencyDescriptor(
                            resolvedDependency.getFile().toPath(),
                            dependency.getDependency().isOptional(),
                            assignedModuleName
                    )
            );
        }

        return dependencies;
    }

    private void createDirectories() {
        if ( !workingDirectory.exists() ) {
            workingDirectory.mkdirs();
        }

        if ( !outputDirectory.exists() ) {
            outputDirectory.mkdirs();
        }
    }
}
