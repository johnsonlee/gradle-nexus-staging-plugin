package io.codearte.gradle.nexus.legacy

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.codearte.gradle.nexus.CloseRepositoryTask
import io.codearte.gradle.nexus.CreateRepositoryTask
import io.codearte.gradle.nexus.NexusStagingExtension
import io.codearte.gradle.nexus.NexusStagingPlugin
import org.gradle.api.Incubating
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.maven.MavenDeployer
import org.gradle.api.plugins.MavenPlugin
import org.gradle.api.tasks.TaskCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.Upload
import org.gradle.plugins.signing.Sign

import javax.annotation.Nonnull

/**
 * Plugin for internal use only with "legacy" UploadArchives mechanism. It's most likely not something that you are looking for.
 */
@CompileStatic
@Incubating
@Slf4j
@SuppressWarnings("UnstableApiUsage")
class NexusUploadStagingPlugin implements Plugin<Project> {

    private static final String UPLOAD_TO_NEXUS_STAGING_TASK_NAME = "uploadArchivesStaging"
    private static final String POINT_UPLOAD_ARCHIVES_TO_EXPLICIT_REPOSITORY = "pointUploadArchivesToExplicitRepository"

    @Override
    void apply(@Nonnull Project project) {
        project.getPluginManager().apply(MavenPlugin)
        project.getPluginManager().apply(NexusStagingPlugin)

        TaskProvider<PointUploadArchivesToExplicitRepositoryTask> pointUploadArchivesToExplicitRepository = project.tasks
            .register(POINT_UPLOAD_ARCHIVES_TO_EXPLICIT_REPOSITORY, PointUploadArchivesToExplicitRepositoryTask, project,
                (NexusStagingExtension) project.getExtensions().getByType(NexusStagingExtension))   //casting due to Idea warning
        pointUploadArchivesToExplicitRepository.configure { Task task ->
            task.setDescription("LEGACY WARNING. Do not use this tasks. Points uploadArchives tasks to explicitly created staging repository in Nexus")
            task.setGroup("release")
        }

        TaskProvider<Task> uploadStagingTask = project.tasks.register(UPLOAD_TO_NEXUS_STAGING_TASK_NAME) { Task task ->
            task.setDescription("LEGACY WARNING. Do not use this tasks. Uploads artifacts to explicitly created staging repository in Nexus")
            task.setGroup("release")
        }

        configureTaskDependencies(project, pointUploadArchivesToExplicitRepository, uploadStagingTask)
    }

    @CompileDynamic
    void configureTaskDependencies(@Nonnull Project project, TaskProvider<PointUploadArchivesToExplicitRepositoryTask> pointUploadArchivesToExplicitRepositoryTask,
                                   TaskProvider<Task> uploadStagingTask) {

        project.afterEvaluate { Project evaluatedProject ->
            TaskCollection<CreateRepositoryTask> createRepositoryTasks = project.tasks.withType(CreateRepositoryTask)

            project.tasks
                .withType(Upload)
//                .matching(uploadTask -> hasMatchingRepositoryUrl(serverUrl)   //Is everything already set/updated here?
                .matching { uploadTask -> !uploadTask.repositories.withType(MavenDeployer).isEmpty() } //Exclude "install" task
                .configureEach { Upload uploadTask ->
                    log.info("Creating extra stating dependencies for task ${uploadTask}")
                    uploadTask.mustRunAfter(pointUploadArchivesToExplicitRepositoryTask)
                    uploadStagingTask.configure { task -> task.dependsOn(uploadTask) }
                }

            //TODO: createRepository should be executed right before uploadArchives - not before compileJava...
            pointUploadArchivesToExplicitRepositoryTask.configure { task -> task.dependsOn(createRepositoryTasks) }
            uploadStagingTask.configure { task ->
                task.dependsOn(createRepositoryTasks, pointUploadArchivesToExplicitRepositoryTask)
            }

            TaskCollection<CloseRepositoryTask> closeRepositoryTasks = project.tasks.withType(CloseRepositoryTask)
            closeRepositoryTasks*.mustRunAfter(uploadStagingTask)

            createRepositoryTasks*.mustRunAfter(project.tasks.withType(Sign))   //to prevent it's execute before compilation where things can easily fail. Sign is not perfect
            createRepositoryTasks*.onlyIf { isInNonSnapshotVersion(project) }
            pointUploadArchivesToExplicitRepositoryTask.configure { task -> task.onlyIf { isInNonSnapshotVersion(project) } }
        }
    }

    private boolean isInNonSnapshotVersion(Project project) {
        //TODO: Should configurable (e.g. disabled on demand) in extension, but there is no separate extension for NexusUploadStagingPlugin
        //      and it would pollute that from NexusStagingPlugin
        return !project.getVersion().toString().endsWith("-SNAPSHOT")
    }
}
