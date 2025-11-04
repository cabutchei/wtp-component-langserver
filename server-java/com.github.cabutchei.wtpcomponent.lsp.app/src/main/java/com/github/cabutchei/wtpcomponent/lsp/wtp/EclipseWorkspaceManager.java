package com.github.cabutchei.wtpcomponent.lsp.wtp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

/**
 * Utility for importing plain file-system projects into an in-memory
 * Eclipse workspace so downstream WTP APIs can reason about them.
 */
public final class EclipseWorkspaceManager {

    private static final Logger LOG = Logger.getLogger(EclipseWorkspaceManager.class.getName());

    public List<IProject> getProjects() {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        return List.of(root.getProjects());
    }

    public void deleteProjects() {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
            List.of(root.getProjects()).forEach(
                p -> {
                    try{
                        p.delete(IResource.NEVER_DELETE_PROJECT_CONTENT, null);
                    } catch (CoreException e) {
                        LOG.log(Level.WARNING, "Failed to delete project " + p.getName(), e);
                    }
                }
                );
        }

    /**
     * Import every folder under {@code workspaceRoot} that contains a {@code .project}
     * descriptor into the Eclipse workspace.
     */
    public List<IProject> importProjects(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");

        if (!Files.isDirectory(workspaceRoot)) {
            LOG.fine(() -> "Skipping workspace import, directory does not exist: " + workspaceRoot);
            return List.of();
        }

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        AtomicInteger imported = new AtomicInteger();
        List<IProject> projects = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(workspaceRoot, 5)) {
            paths.filter(p -> p.getFileName() != null && ".project".equals(p.getFileName().toString()))
                 .forEach(projectFile -> importProjectFile(workspace, root, projectFile, imported)
                     .ifPresent(projects::add));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to scan workspace root for projects: " + workspaceRoot, e);
        }

        LOG.fine(() -> "Imported/updated " + imported.get() + " Eclipse projects from " + workspaceRoot);
        return projects;
    }

    private Optional<IProject> importProjectFile(IWorkspace workspace, IWorkspaceRoot root, Path projectFile, AtomicInteger counter) {
        IProjectDescription description;
        LOG.info(org.eclipse.core.runtime.Path.fromOSString(projectFile.toString()).toString());
        try {
            description = workspace.loadProjectDescription(org.eclipse.core.runtime.Path.fromOSString(projectFile.toString()));
        } catch (CoreException e) {
            LOG.log(Level.WARNING, "Failed to read project description " + projectFile, e);
            return Optional.empty();
        }

        IProject project = root.getProject(description.getName());
        try {
            if (!project.exists()) {
                project.create(description, null);
            }
            if (!project.isOpen()) {
                project.open(null);
            }
            project.setDescription(description, null);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            counter.incrementAndGet();
            return Optional.of(project);
        } catch (CoreException e) {
            LOG.log(Level.WARNING, "Failed to import project " + description.getName(), e);
            return Optional.empty();
        }
    }

    /**
     * Resolve a workspace {@link IFile} for the given file-system path.
     */
    public Optional<IFile> findFile(Path absolutePath) {
        Objects.requireNonNull(absolutePath, "absolutePath");
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IFile file = root.getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(absolutePath.toString()));
        return Optional.ofNullable(file != null && file.exists() ? file : null);
    }
}
