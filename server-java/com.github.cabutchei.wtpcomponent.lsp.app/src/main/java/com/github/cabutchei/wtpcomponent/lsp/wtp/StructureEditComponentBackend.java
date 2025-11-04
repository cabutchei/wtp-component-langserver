package com.github.cabutchei.wtpcomponent.lsp.wtp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.wst.common.componentcore.ComponentCore;
import org.eclipse.wst.common.componentcore.internal.ComponentResource;
import org.eclipse.wst.common.componentcore.internal.StructureEdit;
import org.eclipse.wst.common.componentcore.internal.WorkbenchComponent;
import org.eclipse.wst.common.componentcore.resources.IVirtualComponent;
import org.eclipse.wst.common.componentcore.resources.IVirtualContainer;
import org.eclipse.wst.common.componentcore.resources.IVirtualFolder;
import org.eclipse.wst.common.componentcore.resources.IVirtualReference;
import org.eclipse.wst.common.componentcore.resources.IVirtualResource;
import org.eclipse.wst.common.frameworks.datamodel.DataModelFactory;
import org.eclipse.wst.common.frameworks.datamodel.IDataModel;
import org.eclipse.wst.common.frameworks.datamodel.IDataModelOperation;

import com.github.cabutchei.wtpcomponent.lsp.model.ComponentModel;
import com.github.cabutchei.wtpcomponent.lsp.model.Mapping;

/**
 * Thin wrapper around {@link StructureEdit} for reading and updating
 * {@code org.eclipse.wst.common.component} descriptors.
 */
public final class StructureEditComponentBackend {

    private static final Logger LOG = Logger.getLogger(StructureEditComponentBackend.class.getName());
    private static final String WB_RESOURCE_TAG = "wb-resource";

    private final EclipseWorkspaceManager workspaceManager;
    private final Map<Path, ComponentModel> cache = new ConcurrentHashMap<>();

    public StructureEditComponentBackend(EclipseWorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    }

    public void refreshComponents(Collection<IProject> projects) {
        LOG.info("Refreshing components for " + (projects == null ? 0 : projects.size()) + " projects.");
        if (projects == null) return;
        for (IProject project : projects) {
            if (project == null || !project.isAccessible()) continue;
            locateComponentFile(project).ifPresent(componentPath -> {
                Path key = normalize(componentPath);
                Optional<ComponentModel> model = buildModel(project);
                if (model.isPresent()) {
                    cache.put(key, model.get());
                } else {
                    cache.remove(key);
                }
            });
        }
    }

    public Optional<ComponentModel> readComponent(URI componentUri) {
        LOG.info("READ COMPONENT " + componentUri);
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

        Optional<ComponentModel> model = buildModel(project);
        model.ifPresent(m -> cache.put(key, m));
        return model.map(StructureEditComponentBackend::copyOf);
    }

    private Optional<ComponentModel> buildModel(IProject project) {
        if (project == null || !project.isAccessible()) {
            return Optional.empty();
        }
        IVirtualComponent component = ComponentCore.createComponent(project);
        if (component == null || !component.exists()) {
            LOG.fine(() -> "No virtual component for project " + project.getName());
            return Optional.empty();
        }
        return modelFromVirtualComponent(component);
    }

    public Optional<String> addMapping(URI componentUri, String source, String deployPath) {
        Path path = toPath(componentUri);
        if (path == null) return Optional.empty();

        Optional<IFile> maybeFile = workspaceManager.findFile(path);
        if (maybeFile.isEmpty()) return Optional.empty();

        IFile componentFile = maybeFile.get();
        IProject project = componentFile.getProject();
        if (project == null || !project.isAccessible()) return Optional.empty();

        IDataModel model = DataModelFactory.createDataModel(new ComponentMappingDataModelProvider());
        model.setProperty(ComponentMappingDataModelProperties.COMPONENT_FILE, componentFile);
        model.setProperty(ComponentMappingDataModelProperties.SOURCE_PATH, source);
        model.setProperty(ComponentMappingDataModelProperties.DEPLOY_PATH, deployPath);

        IDataModelOperation op = model.getDefaultOperation();
        IStatus status;
        try {
            status = op.execute(new NullProgressMonitor(), null);
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Component mapping operation execution error", e);
            return Optional.empty();
        }
        if (!status.isOK()) {
            String message = "Component mapping operation failed: " + status.getMessage();
            LOG.log(Level.WARNING, message, status.getException());
            return Optional.empty();
        }

        refreshComponentCache(project, path);
        try {
            return readFile(path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read updated component file: " + path, e);
            return Optional.empty();
        }
    }

    static boolean hasMapping(WorkbenchComponent component, String source, String deploy) {
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

    static String optionalPath(org.eclipse.core.runtime.IPath path) {
        if (path == null) return null;
        String value = path.toPortableString();
        return normalize(value);
    }

    static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static Optional<String> readFile(Path path) throws IOException {
        return Optional.ofNullable(Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null);
    }

    static Path toPath(URI uri) {
        if (uri == null) return null;
        try {
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            return Path.of(uri);
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.FINE, "Unsupported component URI: " + uri, ex);
            return null;
        }
    }

    static void prepare(StructureEdit edit) {
        try {
            edit.prepareProjectComponentsIfNecessary();
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed to prepare StructureEdit model", ex);
        }
    }

    static WorkbenchComponent primaryComponent(StructureEdit edit) {
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

    private Optional<ComponentModel> modelFromVirtualComponent(IVirtualComponent component) {
        if (component == null || !component.exists()) {
            return Optional.empty();
        }
        ComponentModel model = new ComponentModel();
        // collectComponentResources(component, model);
        collectDependentModules(component, model);
        return Optional.of(model);
    }

    private void collectComponentResources(IVirtualComponent component, ComponentModel model) {
        IVirtualFolder root = component.getRootFolder();
        if (root == null) return;

        Set<ComponentResource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<IVirtualContainer> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            IVirtualContainer container = queue.removeFirst();
            try {
                for (IVirtualResource resource : container.members()) {
                    if (resource == null) continue;
                    if (resource instanceof IVirtualContainer nested) {
                        queue.addLast(nested);
                    }
                    ComponentResource componentResource = resource.getAdapter(ComponentResource.class);
                    if (componentResource == null) continue;
                    if (!seen.add(componentResource)) continue;

                    String tag = normalize(componentResource.getTag());
                    if (tag == null) {
                        tag = WB_RESOURCE_TAG;
                    }
                    if (!WB_RESOURCE_TAG.equals(tag)) {
                        continue;
                    }
                    if (!hasAccessibleBackingResource(resource)) {
                        continue;
                    }

                    String source = optionalPath(componentResource.getSourcePath());
                    String deploy = optionalPath(componentResource.getRuntimePath());
                    if (source == null && deploy == null) {
                        continue;
                    }
                    model.getMappings().add(new Mapping(tag, source, deploy));
                }
            } catch (CoreException e) {
                LOG.log(Level.FINE, "Failed to enumerate virtual resources for component " + component.getName(), e);
            }
        }
    }

    private void collectDependentModules(IVirtualComponent component, ComponentModel model) {
        IVirtualReference[] references = component.getReferences();
        if (references == null || references.length == 0) {
            return;
        }
        for (IVirtualReference reference : references) {
            if (reference == null) continue;
            String deploy = optionalPath(reference.getRuntimePath());
            String archive = normalize(reference.getArchiveName());
            if (archive == null) {
                IVirtualComponent child = reference.getReferencedComponent();
                if (child != null) {
                    IProject childProject = child.getProject();
                    if (childProject != null && childProject.exists()) {
                        archive = childProject.getName();
                    } else {
                        archive = normalize(child.getDeployedName());
                        if (archive == null) {
                            archive = child.getName();
                        }
                    }
                }
            }
            if (archive == null && deploy == null) {
                continue;
            }
            model.getMappings().add(new Mapping("dependent-module", archive, deploy));
        }
    }

    private static boolean hasAccessibleBackingResource(IVirtualResource resource) {
        IResource[] underlying = resource.getUnderlyingResources();
        if (underlying == null || underlying.length == 0) {
            return true;
        }
        for (IResource candidate : underlying) {
            if (candidate != null && candidate.exists()) {
                return true;
            }
        }
        return false;
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

    private void refreshComponentCache(IProject project, Path componentPath) {
        Path key = normalize(componentPath);
        Optional<ComponentModel> model = buildModel(project);
        if (model.isPresent()) {
            cache.put(key, model.get());
        } else {
            cache.remove(key);
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
