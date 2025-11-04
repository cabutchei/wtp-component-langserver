package com.github.cabutchei.wtpcomponent.lsp.wtp;

import static com.github.cabutchei.wtpcomponent.lsp.wtp.ComponentMappingDataModelProperties.COMPONENT_FILE;
import static com.github.cabutchei.wtpcomponent.lsp.wtp.ComponentMappingDataModelProperties.DEPLOY_PATH;
import static com.github.cabutchei.wtpcomponent.lsp.wtp.ComponentMappingDataModelProperties.SOURCE_PATH;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.wst.common.componentcore.internal.ComponentResource;
import org.eclipse.wst.common.componentcore.internal.ComponentcoreFactory;
import org.eclipse.wst.common.componentcore.internal.StructureEdit;
import org.eclipse.wst.common.componentcore.internal.WorkbenchComponent;
import org.eclipse.wst.common.frameworks.datamodel.AbstractDataModelOperation;
import org.eclipse.wst.common.frameworks.datamodel.IDataModel;

/**
 * {@link AbstractDataModelOperation} responsible for inserting a new mapping into a
 * WTP component descriptor (.component / org.eclipse.wst.common.component).
 */
final class ComponentMappingDataModelOperation extends AbstractDataModelOperation {

    private static final String PLUGIN_ID = "com.github.cabutchei.wtpcomponent.lsp.app";

    ComponentMappingDataModelOperation(IDataModel model) {
        super(model);
    }

    @Override
    public IStatus execute(IProgressMonitor monitor, IAdaptable info) {
        IFile componentFile = (IFile) getDataModel().getProperty(COMPONENT_FILE);
        if (componentFile == null || !componentFile.exists()) {
            return status(IStatus.ERROR, "Component file is unavailable.");
        }

        String sourcePath = StructureEditComponentBackend.normalize(
            (String) getDataModel().getProperty(SOURCE_PATH));
        String deployPath = StructureEditComponentBackend.normalize(
            (String) getDataModel().getProperty(DEPLOY_PATH));

        IProject project = componentFile.getProject();
        if (project == null || !project.isAccessible()) {
            return status(IStatus.ERROR, "Target project is not accessible.");
        }

        StructureEdit edit = StructureEdit.getStructureEditForWrite(project);
        if (edit == null) {
            return status(IStatus.ERROR, "Unable to acquire StructureEdit for " + project.getName());
        }

        IProgressMonitor effectiveMonitor = monitor != null ? monitor : new NullProgressMonitor();
        try {
            StructureEditComponentBackend.prepare(edit);
            WorkbenchComponent component = StructureEditComponentBackend.primaryComponent(edit);
            if (component == null) {
                return status(IStatus.ERROR, "Unable to resolve primary component for project " + project.getName());
            }

            if (StructureEditComponentBackend.hasMapping(component, sourcePath, deployPath)) {
                return Status.OK_STATUS;
            }

            ComponentResource resource = ComponentcoreFactory.eINSTANCE.createComponentResource();
            if (sourcePath != null) {
                resource.setSourcePath(new org.eclipse.core.runtime.Path(sourcePath));
            }
            if (deployPath != null) {
                resource.setRuntimePath(new org.eclipse.core.runtime.Path(deployPath));
            }

            component.getResources().add(resource);
            edit.saveIfNecessary(effectiveMonitor);
            refresh(componentFile, effectiveMonitor);
            return Status.OK_STATUS;
        } catch (Exception ex) {
            return status(IStatus.ERROR, "Failed to update component descriptor: " + ex.getMessage(), ex);
        } finally {
            edit.dispose();
        }
    }

    private static void refresh(IFile file, IProgressMonitor monitor) throws CoreException {
        file.refreshLocal(IResource.DEPTH_ZERO, monitor);
    }

    private static IStatus status(int severity, String message) {
        return new Status(severity, PLUGIN_ID, message);
    }

    private static IStatus status(int severity, String message, Throwable cause) {
        return new Status(severity, PLUGIN_ID, message, cause);
    }
}
