package com.github.cabutchei.wtpcomponent.lsp.wtp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.wst.common.componentcore.internal.ComponentResource;
import org.eclipse.wst.common.componentcore.internal.ComponentcoreFactory;
import org.eclipse.wst.common.componentcore.internal.StructureEdit;
import org.eclipse.wst.common.componentcore.internal.WorkbenchComponent;

import com.github.cabutchei.wtpcomponent.lsp.model.ComponentModel;
import com.github.cabutchei.wtpcomponent.lsp.model.Mapping;

/**
 * Thin wrapper around {@link StructureEdit} for reading and updating
 * {@code org.eclipse.wst.common.component} descriptors.
 */
public final class StructureEditComponentBackend {

    private static final Logger LOG = Logger.getLogger(StructureEditComponentBackend.class.getName());

    private final EclipseWorkspaceManager workspaceManager;

    public StructureEditComponentBackend(EclipseWorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    }

    public Optional<ComponentModel> readComponent(URI componentUri) {
        Path path = toPath(componentUri);
        if (path == null) return Optional.empty();

        Optional<IFile> maybeFile = workspaceManager.findFile(path);
        if (maybeFile.isEmpty()) return Optional.empty();

        IProject project = maybeFile.get().getProject();
        if (project == null || !project.isAccessible()) return Optional.empty();

        StructureEdit edit = StructureEdit.getStructureEditForRead(project);
        if (edit == null) return Optional.empty();

        try {
            WorkbenchComponent component = edit.getComponent();
            if (component == null) return Optional.empty();

            ComponentModel model = new ComponentModel();
            component.getResources().forEach(res -> {
                if (res instanceof ComponentResource resource) {
                    String source = optionalPath(resource.getSourcePath());
                    String deploy = optionalPath(resource.getRuntimePath());
                    model.getMappings().add(new Mapping("wb-resource", source, deploy));
                }
            });
            return Optional.of(model);
        } finally {
            edit.dispose();
        }
    }

    public Optional<String> addMapping(URI componentUri, String source, String deployPath) {
        Path path = toPath(componentUri);
        if (path == null) return Optional.empty();

        Optional<IFile> maybeFile = workspaceManager.findFile(path);
        if (maybeFile.isEmpty()) return Optional.empty();

        IProject project = maybeFile.get().getProject();
        if (project == null || !project.isAccessible()) return Optional.empty();

        StructureEdit edit = StructureEdit.getStructureEditForWrite(project);
        if (edit == null) return Optional.empty();

        try {
            WorkbenchComponent component = edit.getComponent();
            if (component == null) return Optional.empty();

            if (hasMapping(component, source, deployPath)) {
                return readFile(path);
            }

            ComponentResource resource = ComponentcoreFactory.eINSTANCE.createComponentResource();
            if (source != null && !source.isBlank()) {
                resource.setSourcePath(new org.eclipse.core.runtime.Path(source));
            }
            if (deployPath != null && !deployPath.isBlank()) {
                resource.setRuntimePath(new org.eclipse.core.runtime.Path(deployPath));
            }
            component.getResources().add(resource);
            edit.saveIfNecessary(null);
            return readFile(path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to update component file via StructureEdit: " + path, e);
            return Optional.empty();
        } finally {
            edit.dispose();
        }
    }

    private static boolean hasMapping(WorkbenchComponent component, String source, String deploy) {
        for (Object obj : component.getResources()) {
            if (obj instanceof ComponentResource resource) {
                boolean sameSource = Objects.equals(optionalPath(resource.getSourcePath()), normalize(source));
                boolean sameDeploy = Objects.equals(optionalPath(resource.getRuntimePath()), normalize(deploy));
                if (sameSource && sameDeploy) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String optionalPath(org.eclipse.core.runtime.IPath path) {
        if (path == null) return null;
        String value = path.toPortableString();
        return normalize(value);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Optional<String> readFile(Path path) throws IOException {
        return Optional.ofNullable(Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null);
    }

    private static Path toPath(URI uri) {
        if (uri == null) return null;
        try {
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            return Path.of(uri);
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.FINE, "Unsupported component URI: " + uri, ex);
            return null;
        }
    }
}
