package org.jetbrains.intellij

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.query.ArtifactResolutionQuery
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.ivy.internal.artifact.DefaultIvyArtifact
import org.gradle.api.publish.ivy.internal.publication.DefaultIvyConfiguration
import org.gradle.api.publish.ivy.internal.publication.DefaultIvyPublicationIdentity
import org.gradle.api.publish.ivy.internal.publisher.IvyDescriptorFileGenerator
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.SourceSet
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable

class IntelliJPlugin implements Plugin<Project> {
    private static final def LOG = Logging.getLogger(IntelliJPlugin.class)
    private static final EXTENSION_NAME = "intellij"
    private static final String DEFAULT_IDEA_VERSION = "LATEST-EAP-SNAPSHOT"

    @Override
    def void apply(Project project) {
        project.getPlugins().apply(JavaPlugin.class)
        def intellijExtension = project.extensions.create(EXTENSION_NAME, IntelliJPluginExtension.class)
        intellijExtension.with {
            version = DEFAULT_IDEA_VERSION
        }
        configureConfigurations(project, intellijExtension)
        configureSetPluginVersionTask(project)
    }

    private static def configureConfigurations(@NotNull Project project,
                                               @NotNull IntelliJPluginExtension intelliJPluginExtension) {
        def configuration = project.configurations.create("intellij")
                .setVisible(true)
                .setDescription("The IntelliJ IDEA Community Edition artifact to be used for this project.")

        project.afterEvaluate {
            LOG.info("Preparing IntelliJ IDEA dependency task")
            if (configuration.dependencies.empty) {
                def version = intelliJPluginExtension.version
                LOG.info("Adding IntelliJ IDEA repository")

                def releaseType = version.contains('SNAPSHOT') ? 'snapshots' : 'releases'
                project.repositories.maven {
                    it.url = "https://www.jetbrains.com/intellij-repository/${releaseType}"
                }

                LOG.info("Adding IntelliJ IDEA dependency")
                project.dependencies.add(configuration.name, "com.jetbrains.intellij.idea:ideaIC:${version}")
                LOG.info("IDEA zip: " + configuration.singleFile.path)

                def ideaDirectory = ideaDirectory(project, configuration.singleFile)
                def sourcesFile = ideaSourcesFile(project, configuration)
                def moduleName = createIvyRepo(project, intelliJPluginExtension.plugins, ideaDirectory, sourcesFile, version)

                project.repositories.ivy { repo ->
                    repo.url = ideaDirectory
                    if (sourcesFile != null) {
                        repo.artifactPattern("${sourcesFile.parent}/[artifact]-${version}-[classifier].[ext]")
                    }
                    repo.artifactPattern("${ideaDirectory.path}/com.jetbrains/${moduleName}/${version}/[artifact]-${project.name}.[ext]")
                    repo.artifactPattern("${ideaDirectory.path}/lib/[artifact].[ext]")
                    intelliJPluginExtension.plugins.each {
                        repo.artifactPattern("${ideaDirectory.path}/plugins/${it}/lib/[artifact].[ext]")
                    }
                    repo.artifactPattern("${System.getProperty("java.home")}/../lib/[artifact].[ext]")
                }
                project.dependencies.add(JavaPlugin.COMPILE_CONFIGURATION_NAME, [
                        group: 'com.jetbrains', name: moduleName, version: version, configuration: 'compile'
                ])
                project.dependencies.add(JavaPlugin.RUNTIME_CONFIGURATION_NAME, [
                        group: 'com.jetbrains', name: moduleName, version: version, configuration: 'runtime'
                ])
            }
        }
    }

    private static File ideaSourcesFile(@NotNull Project project, @NotNull Configuration configuration) {
        try {
            Collection<ComponentIdentifier> components = new ArrayList<>()
            configuration.getResolvedConfiguration().getLenientConfiguration().getArtifacts(Specs.SATISFIES_ALL).each {
                def id = it.getModuleVersion().getId()
                components.add(new DefaultModuleComponentIdentifier(id.getGroup(), id.getName(), id.getVersion()))
            }

            ArtifactResolutionQuery query = project.dependencies.createArtifactResolutionQuery();
            query.forComponents(components);
            query.withArtifacts(JvmLibrary.class, SourcesArtifact.class);
            for (def component : query.execute().getResolvedComponents()) {
                for (def artifact : component.getArtifacts(SourcesArtifact.class)) {
                    if (artifact instanceof ResolvedArtifactResult) {
                        return ((ResolvedArtifactResult) artifact).getFile();
                    }
                }
            }
        } catch (Throwable ignored) {
            LOG.info("Cannot download IntelliJ sources")
        }
        return null
    }

    private static void configureSetPluginVersionTask(@NotNull Project project) {
        project.afterEvaluate {
            JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention.class);
            SourceSet mainSourceSet = javaConvention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            PluginVersionTask task = project.tasks.create(PluginVersionTask.NAME, PluginVersionTask.class);
            task.setSourceSet(mainSourceSet);
            project.getTasksByName(JavaPlugin.JAR_TASK_NAME, false)*.dependsOn(task)
        }
    }

    @NotNull
    private static File ideaDirectory(@NotNull Project project, @NotNull File zipFile) {
        def directoryName = zipFile.name - ".zip"
        def cacheDirectory = new File(zipFile.parent, directoryName)
        def markerFile = new File(cacheDirectory, "markerFile")
        if (!markerFile.exists()) {
            if (cacheDirectory.exists()) cacheDirectory.deleteDir()
            cacheDirectory.mkdir()
            LOG.info("Unzipping idea")
            project.copy {
                it.from(project.zipTree(zipFile))
                it.into(cacheDirectory)
            }
            markerFile.createNewFile()
        }
        return cacheDirectory;
    }

    private static def createIvyRepo(@NotNull Project project, @NotNull String[] bundledPlugins,
                                     @NotNull File ideaDirectory, @Nullable File ideaSourcesFile,
                                     @NotNull String version) {
        def moduleName = "ideaIC"
        def generator = new IvyDescriptorFileGenerator(new DefaultIvyPublicationIdentity("com.jetbrains", moduleName, version))
        generator.addConfiguration(new DefaultIvyConfiguration("compile"))
        generator.addConfiguration(new DefaultIvyConfiguration("sources"))
        generator.addConfiguration(new DefaultIvyConfiguration("runtime"))

        project.fileTree(ideaDirectory).include("lib*/*.jar").files.each {
            def artifact = new DefaultIvyArtifact(it, it.name - ".jar", "jar", "jar", null)
            artifact.conf = "compile"
            generator.addArtifact(artifact)
        }

        def bundledPluginJars = project.fileTree(ideaDirectory)
        bundledPlugins.each {
            bundledPluginJars.include("plugins/${it}/lib/*.jar")
        }
        bundledPluginJars.files.each {
            def artifact = new DefaultIvyArtifact(it, it.name - ".jar", "jar", "jar", null)
            artifact.conf = "runtime"
            generator.addArtifact(artifact)
        }

        def toolsJars = project.fileTree("${System.getProperty("java.home")}/../lib", {
            ConfigurableFileTree tree ->
                tree.include {
                    "*tools.jar"
                }
        })
        toolsJars.files.each {
            def artifact = new DefaultIvyArtifact(it, it.name - ".jar", "jar", "jar", null)
            artifact.conf = "runtime"
            generator.addArtifact(artifact)
        }

        def artifact = new DefaultIvyArtifact(ideaSourcesFile, "ideaIC", "jar", "sources", "sources")
        artifact.conf = "sources"
        generator.addArtifact(artifact)

        def parentDirectory = new File(ideaDirectory, "com.jetbrains/${moduleName}/${version}")
        parentDirectory.mkdirs()
        generator.writeTo(new File(parentDirectory, "ivy-${project.name}.xml"))
        return moduleName
    }
}