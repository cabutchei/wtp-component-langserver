package com.github.cabutchei.wtpcomponent.lsp.wtp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.eclipse.core.runtime.CoreException;

/**
 * Utility for importing plain file-system projects into an in-memory
 * Eclipse workspace so downstream WTP APIs can reason about them.
 */
public final class EclipseWorkspaceManager {

    private static final Logger LOG = Logger.getLogger(EclipseWorkspaceManager.class.getName());

    /**
     * Import every folder under {@code workspaceRoot} that contains a {@code .project}
     * descriptor into the Eclipse workspace.
     */
    public void importProjects(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");

        if (!Files.isDirectory(workspaceRoot)) {
            LOG.fine(() -> "Skipping workspace import, directory does not exist: " + workspaceRoot);
            return;
        }

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        AtomicInteger imported = new AtomicInteger();

        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            paths.filter(p -> p.getFileName() != null && ".project".equals(p.getFileName().toString()))
                 .forEach(projectFile -> importProjectFile(workspace, root, projectFile, imported));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to scan workspace root for projects: " + workspaceRoot, e);
        }

        LOG.fine(() -> "Imported/updated " + imported.get() + " Eclipse projects from " + workspaceRoot);
    }

    private void importProjectFile(IWorkspace workspace, IWorkspaceRoot root, Path projectFile, AtomicInteger counter) {
        IProjectDescription description;
        try {
            description = workspace.loadProjectDescription(org.eclipse.core.runtime.Path.fromOSString(projectFile.toString()));
        } catch (CoreException e) {
            LOG.log(Level.WARNING, "Failed to read project description " + projectFile, e);
            return;
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
            counter.incrementAndGet();
        } catch (CoreException e) {
            LOG.log(Level.WARNING, "Failed to import project " + description.getName(), e);
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
