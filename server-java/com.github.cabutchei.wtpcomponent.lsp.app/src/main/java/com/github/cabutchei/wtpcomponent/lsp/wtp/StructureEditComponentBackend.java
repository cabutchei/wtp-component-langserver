package com.github.cabutchei.wtpcomponent.lsp.wtp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
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
    private final Map<Path, ComponentModel> cache = new ConcurrentHashMap<>();

    public StructureEditComponentBackend(EclipseWorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    }

    public void refreshComponents(Collection<IProject> projects) {
        if (projects == null) return;
        for (IProject project : projects) {
            if (project == null || !project.isAccessible()) continue;
            locateComponentFile(project).ifPresent(componentPath -> {
                StructureEdit edit = StructureEdit.getStructureEditForRead(project);
                if (edit == null) return;
                try {
                    prepare(edit);
                    WorkbenchComponent component = primaryComponent(edit);
                    if (component != null) {
                        cacheComponent(componentPath, component);
                    }
                } finally {
                    edit.dispose();
                }
            });
        }
    }

    public Optional<ComponentModel> readComponent(URI componentUri) {
        Path path = toPath(componentUri);
        if (path == null) return Optional.empty();
        Path key = normalize(path);

        ComponentModel cached = cache.get(key);
        if (cached != null) {
            return Optional.of(copyOf(cached));
        }

        Optional<IFile> maybeFile = workspaceManager.findFile(path);
        if (maybeFile.isEmpty()) return Optional.empty();

        IProject project = maybeFile.get().getProject();
        if (project == null || !project.isAccessible()) return Optional.empty();

        StructureEdit edit = StructureEdit.getStructureEditForRead(project);
        if (edit == null) return Optional.empty();

        try {
            prepare(edit);
            WorkbenchComponent component = primaryComponent(edit);
            if (component == null) return Optional.empty();

            ComponentModel model = modelFromComponent(component);
            cache.put(key, model);
            return Optional.of(copyOf(model));
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
            prepare(edit);
            WorkbenchComponent component = primaryComponent(edit);
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
            cacheComponent(path, component);
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

    private static void prepare(StructureEdit edit) {
        try {
            edit.prepareProjectComponentsIfNecessary();
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed to prepare StructureEdit model", ex);
        }
    }

    private static WorkbenchComponent primaryComponent(StructureEdit edit) {
        WorkbenchComponent component = edit.getComponent();
        if (component != null) {
            return component;
        }
        WorkbenchComponent[] modules = edit.getWorkbenchModules();
        if (modules != null && modules.length > 0) {
            return modules[0];
        }
        return edit.getFirstModule();
    }

    private void cacheComponent(Path componentPath, WorkbenchComponent component) {
        cache.put(normalize(componentPath), modelFromComponent(component));
    }

    private static ComponentModel modelFromComponent(WorkbenchComponent component) {
        ComponentModel model = new ComponentModel();
        component.getResources().forEach(res -> {
            if (res instanceof ComponentResource resource) {
                if (!resourceExists(resource)) return;
                String source = optionalPath(resource.getSourcePath());
                String deploy = optionalPath(resource.getRuntimePath());
                Mapping mapping = new Mapping("wb-resource", source, deploy);
                model.getMappings().add(mapping);
            }
        });
        return model;
    }

    private static ComponentModel copyOf(ComponentModel original) {
        ComponentModel copy = new ComponentModel();
        for (Mapping mapping : original.getMappings()) {
            Mapping m = new Mapping();
            m.setTag(mapping.getTag());
            m.setSource(mapping.getSource());
            m.setDeployPath(mapping.getDeployPath());
            copy.getMappings().add(m);
        }
        return copy;
    }

    private static boolean resourceExists(ComponentResource resource) {
        if (resource == null) return false;
        if (resource.getSourcePath() == null) {
            // Allow entries without explicit source (e.g. dependent modules)
            return true;
        }
        try {
            IResource res = StructureEdit.getEclipseResource(resource);
            return res != null && res.exists();
        } catch (Exception ex) {
            LOG.log(Level.FINEST, "Failed to resolve resource " + resource, ex);
            return false;
        }
    }

    private Optional<Path> locateComponentFile(IProject project) {
        try {
            URI location = project.getLocationURI();
            if (location == null) return Optional.empty();
            Path projectPath = Path.of(location);
            Path defaultPath = projectPath.resolve(".settings").resolve("org.eclipse.wst.common.component");
            if (Files.exists(defaultPath)) {
                return Optional.of(defaultPath);
            }
            try (Stream<Path> stream = Files.walk(projectPath, 4)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".component") || "org.eclipse.wst.common.component".equals(name);
                    })
                    .findFirst();
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to locate component file for project " + project.getName(), e);
        }
        return Optional.empty();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
