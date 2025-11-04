package com.github.cabutchei.wtpcomponent.lsp.wtp;

import java.util.Set;

import org.eclipse.wst.common.frameworks.datamodel.AbstractDataModelProvider;
import org.eclipse.wst.common.frameworks.datamodel.IDataModelOperation;

/**
 * Simplified data model provider that exposes the state required to update WTP
 * {@code org.eclipse.wst.common.component} descriptors using {@link IDataModelOperation}.
 */
public final class ComponentMappingDataModelProvider extends AbstractDataModelProvider {

    private static final Set<String> PROPERTY_NAMES = Set.of(
        ComponentMappingDataModelProperties.COMPONENT_FILE,
        ComponentMappingDataModelProperties.SOURCE_PATH,
        ComponentMappingDataModelProperties.DEPLOY_PATH
    );

    @Override
    public Set<String> getPropertyNames() {
        return PROPERTY_NAMES;
    }

    @Override
    public IDataModelOperation getDefaultOperation() {
        return new ComponentMappingDataModelOperation(getDataModel());
    }
}
