/*
 * (C) Copyright 2014-2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     vpasquier <vpasquier@nuxeo.com>
 *     ajusto <ajusto@nuxeo.com>
 *     Thibaud Arguillere
 */
package org.nuxeo.binary.metadata.internals;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.binary.metadata.api.BinaryMetadataConstants;
import org.nuxeo.binary.metadata.api.BinaryMetadataException;
import org.nuxeo.binary.metadata.api.BinaryMetadataProcessor;
import org.nuxeo.binary.metadata.api.BinaryMetadataService;
import org.nuxeo.common.xmap.registry.MapRegistry;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PropertyException;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.blob.BlobManager;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.platform.actions.ActionContext;
import org.nuxeo.ecm.platform.actions.ELActionContext;
import org.nuxeo.ecm.platform.actions.ejb.ActionManager;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 7.1
 */
public class BinaryMetadataServiceImpl implements BinaryMetadataService {

    private static final Logger log = LogManager.getLogger(BinaryMetadataServiceImpl.class);

    protected MapRegistry mappingRegistry;

    protected MapRegistry processorRegistry;

    protected List<MetadataRuleDescriptor> rules;

    protected BinaryMetadataServiceImpl(MapRegistry mappingRegistry, MapRegistry processorRegistry,
            MapRegistry ruleRegistry) {
        this.mappingRegistry = mappingRegistry;
        this.processorRegistry = processorRegistry;
        List<MetadataRuleDescriptor> sortedRules = ruleRegistry.getContributionValues();
        sortedRules.sort(MetadataRuleDescriptor.COMPARATOR);
        this.rules = sortedRules;
    }

    @Override
    public Map<String, Object> readMetadata(String processorName, Blob blob, List<String> metadataNames,
            boolean ignorePrefix) {
        BinaryMetadataProcessor processor = getProcessor(processorName);
        return processor.readMetadata(blob, metadataNames, ignorePrefix);
    }

    @Override
    public Map<String, Object> readMetadata(Blob blob, List<String> metadataNames, boolean ignorePrefix) {
        BinaryMetadataProcessor processor = getProcessor(BinaryMetadataConstants.EXIF_TOOL_CONTRIBUTION_ID);
        return processor.readMetadata(blob, metadataNames, ignorePrefix);
    }

    @Override
    public Map<String, Object> readMetadata(Blob blob, boolean ignorePrefix) {
        BinaryMetadataProcessor processor = getProcessor(BinaryMetadataConstants.EXIF_TOOL_CONTRIBUTION_ID);
        return processor.readMetadata(blob, ignorePrefix);
    }

    @Override
    public Map<String, Object> readMetadata(String processorName, Blob blob, boolean ignorePrefix) {
        BinaryMetadataProcessor processor = getProcessor(processorName);
        return processor.readMetadata(blob, ignorePrefix);
    }

    @Override
    public Blob writeMetadata(String processorName, Blob blob, Map<String, Object> metadata, boolean ignorePrefix) {
        BinaryMetadataProcessor processor = getProcessor(processorName);
        return processor.writeMetadata(blob, metadata, ignorePrefix);
    }

    @Override
    public Blob writeMetadata(Blob blob, Map<String, Object> metadata, boolean ignorePrefix) {
        BinaryMetadataProcessor processor = getProcessor(BinaryMetadataConstants.EXIF_TOOL_CONTRIBUTION_ID);
        return processor.writeMetadata(blob, metadata, ignorePrefix);
    }

    @Override
    public Blob writeMetadata(String processorName, Blob blob, String mappingDescriptorId, DocumentModel doc) {
        BinaryMetadataProcessor processor = getProcessor(processorName);
        return mappingRegistry.getContribution(mappingDescriptorId)
                              .map(MetadataMappingDescriptor.class::cast)
                              .map(desc -> {
                                  Map<String, Object> metadataMapping = new HashMap<>();
                                  desc.getMetadataDescriptors()
                                      .forEach(md -> metadataMapping.put(md.getName(),
                                              doc.getPropertyValue(md.getXpath())));
                                  return processor.writeMetadata(blob, metadataMapping, desc.ignorePrefix());
                              })
                              .orElseThrow(() -> new BinaryMetadataException(
                                      String.format("Unknown mapping for id '%s'", mappingDescriptorId)));
    }

    @Override
    public Blob writeMetadata(Blob blob, String mappingDescriptorId, DocumentModel doc) {
        return writeMetadata(BinaryMetadataConstants.EXIF_TOOL_CONTRIBUTION_ID, blob, mappingDescriptorId, doc);
    }

    @Override
    public void writeMetadata(DocumentModel doc) {
        // Check if rules applying for this document.
        ActionContext actionContext = createActionContext(doc);
        Set<MetadataRuleDescriptor> ruleDescriptors = checkFilter(actionContext);
        List<String> mappingDescriptorIds = new ArrayList<>();
        for (MetadataRuleDescriptor ruleDescriptor : ruleDescriptors) {
            mappingDescriptorIds.addAll(ruleDescriptor.getMetadataMappingIdDescriptors());
        }
        if (mappingDescriptorIds.isEmpty()) {
            return;
        }

        // For each mapping descriptors, overriding mapping document properties.
        for (String mappingDescriptorId : mappingDescriptorIds) {
            Optional<MetadataMappingDescriptor> optDesc = mappingRegistry.getContribution(mappingDescriptorId);
            if (optDesc.isEmpty()) {
                log.warn(
                        "Missing binary metadata descriptor with id: {}. Or check your rule contribution with proper metadataMapping-id.",
                        mappingDescriptorId);
                continue;
            }
            writeMetadata(doc, mappingDescriptorId);
        }
    }

    @Override
    public void writeMetadata(DocumentModel doc, String mappingDescriptorId) {
        // Creating mapping properties Map.
        Map<String, String> metadataMapping = new HashMap<>();
        List<String> blobMetadata = new ArrayList<>();
        MetadataMappingDescriptor desc = (MetadataMappingDescriptor) //
        mappingRegistry.getContribution(mappingDescriptorId)
                       .orElseThrow(() -> new BinaryMetadataException(
                               String.format("Unknown mapping for id '%s'", mappingDescriptorId)));
        boolean ignorePrefix = desc.ignorePrefix();
        // Extract blob from the contributed xpath
        Blob blob = doc.getProperty(desc.getBlobXPath()).getValue(Blob.class);
        if (blob != null && desc.getMetadataDescriptors() != null && !desc.getMetadataDescriptors().isEmpty()) {
            for (MetadataMappingDescriptor.MetadataDescriptor metadataDescriptor : desc.getMetadataDescriptors()) {
                metadataMapping.put(metadataDescriptor.getName(), metadataDescriptor.getXpath());
                blobMetadata.add(metadataDescriptor.getName());
            }

            // Extract metadata from binary.
            String processorId = desc.getProcessor();
            Map<String, Object> blobMetadataOutput;
            if (processorId != null) {
                blobMetadataOutput = readMetadata(processorId, blob, blobMetadata, ignorePrefix);

            } else {
                blobMetadataOutput = readMetadata(blob, blobMetadata, ignorePrefix);
            }

            // Write doc properties from outputs.
            for (String metadata : blobMetadataOutput.keySet()) {
                Object metadataValue = blobMetadataOutput.get(metadata);
                boolean metadataIsArray = metadataValue instanceof Object[] || metadataValue instanceof List;
                String property = metadataMapping.get(metadata);
                if (!(metadataValue instanceof Date) && !(metadataValue instanceof Collection) && !metadataIsArray) {
                    metadataValue = metadataValue.toString();
                }
                if (metadataValue instanceof String) {
                    // sanitize string for PostgreSQL textual storage
                    metadataValue = ((String) metadataValue).replace("\u0000", "");
                }
                try {
                    if (doc.getProperty(property).isList()) {
                        if (!metadataIsArray) {
                            metadataValue = Arrays.asList(metadataValue);
                        }
                    } else {
                        if (metadataIsArray) {
                            if (metadataValue instanceof Object[]) {
                                metadataValue = Arrays.asList((Object[]) metadataValue);
                            } else {
                                metadataValue = metadataValue.toString();
                            }
                        }
                    }
                    doc.setPropertyValue(property, (Serializable) metadataValue);
                } catch (PropertyException e) {
                    Object value = metadataValue;
                    log.warn("Failed to set property: {} to value: {} from metadata: {} in: {} in document: {}: {}",
                            () -> property, () -> value, () -> metadata, desc::getBlobXPath, doc::getRef,
                            e::getMessage);
                    log.debug(e, e);
                }
            }
        }
    }

    /*--------------------- Event Service --------------------------*/

    @Override
    public void handleSyncUpdate(DocumentModel doc) {
        List<MetadataMappingDescriptor> syncMappingDescriptors = getSyncMapping(doc);
        if (syncMappingDescriptors != null) {
            handleUpdate(syncMappingDescriptors, doc);
        }
    }

    @Override
    public void handleUpdate(List<MetadataMappingDescriptor> mappingDescriptors, DocumentModel doc) {
        for (MetadataMappingDescriptor mappingDescriptor : mappingDescriptors) {
            Property fileProp = doc.getProperty(mappingDescriptor.getBlobXPath());
            Blob blob = fileProp.getValue(Blob.class);
            if (blob != null) {
                boolean isDirtyMapping = isDirtyMapping(mappingDescriptor, doc);
                if (isDirtyMapping) {
                    if (!mappingDescriptor.isReadOnly()) {
                        BlobManager blobManager = Framework.getService(BlobManager.class);
                        BlobProvider blobProvider = blobManager.getBlobProvider(blob);
                        // do not write metadata in blobs from providers that don't support sync
                        if (blobProvider != null && !blobProvider.supportsSync()) {
                            return;
                        }
                        // if document metadata dirty, write metadata from doc to Blob
                        Blob newBlob = writeMetadata(mappingDescriptor.getProcessor(), fileProp.getValue(Blob.class),
                                mappingDescriptor.getId(), doc);
                        fileProp.setValue(newBlob);
                    }
                } else if (fileProp.isDirty()) {
                    // if Blob dirty and document metadata not dirty, write metadata from Blob to doc
                    writeMetadata(doc);
                }
            }
        }
    }

    /*--------------------- Utils --------------------------*/

    /**
     * Check for each Binary Rule if the document is accepted or not.
     *
     * @return the list of metadata which should be processed sorted by rules order. (high to low priority)
     */
    protected Set<MetadataRuleDescriptor> checkFilter(final ActionContext actionContext) {
        final ActionManager actionService = Framework.getService(ActionManager.class);
        return rules.stream().filter(ruleDescriptor -> {
            for (String filterId : ruleDescriptor.getFilterIds()) {
                if (!actionService.checkFilter(filterId, actionContext)) {
                    return false;
                }
            }
            return true;
        }).collect(Collectors.toSet());
    }

    protected ActionContext createActionContext(DocumentModel doc) {
        ActionContext actionContext = new ELActionContext();
        actionContext.setCurrentDocument(doc);
        CoreSession coreSession = doc.getCoreSession();
        actionContext.setDocumentManager(coreSession);
        if (coreSession != null) {
            actionContext.setCurrentPrincipal(coreSession.getPrincipal());
        }
        return actionContext;
    }

    protected BinaryMetadataProcessor getProcessor(String processorId) throws BinaryMetadataException {
        return processorRegistry.getContribution(processorId)
                                .map(MetadataProcessorDescriptor.class::cast)
                                .map(desc -> desc.processor)
                                .orElseThrow(() -> new BinaryMetadataException(
                                        String.format("Unknown processor for id '%s'", processorId)));
    }

    /**
     * @return Dirty metadata from metadata mapping contribution and handle async processes.
     */
    public List<MetadataMappingDescriptor> getSyncMapping(DocumentModel doc) {
        // Check if rules applying for this document.
        ActionContext actionContext = createActionContext(doc);
        Set<MetadataRuleDescriptor> ruleDescriptors = checkFilter(actionContext);
        Set<String> syncMappingDescriptorIds = new HashSet<>();
        HashSet<String> asyncMappingDescriptorIds = new HashSet<>();
        for (MetadataRuleDescriptor ruleDescriptor : ruleDescriptors) {
            if (ruleDescriptor.getIsAsync()) {
                asyncMappingDescriptorIds.addAll(ruleDescriptor.getMetadataMappingIdDescriptors());
                continue;
            }
            syncMappingDescriptorIds.addAll(ruleDescriptor.getMetadataMappingIdDescriptors());
        }

        // Handle async rules which should be taken into account in async listener.
        if (!asyncMappingDescriptorIds.isEmpty()) {
            doc.putContextData(BinaryMetadataConstants.ASYNC_BINARY_METADATA_EXECUTE, Boolean.TRUE);
            doc.putContextData(BinaryMetadataConstants.ASYNC_MAPPING_RESULT,
                    (Serializable) getMapping(asyncMappingDescriptorIds));
        }

        if (syncMappingDescriptorIds.isEmpty()) {
            return null;
        }
        return getMapping(syncMappingDescriptorIds);
    }

    protected List<MetadataMappingDescriptor> getMapping(Set<String> mappingDescriptorIds) {
        // For each mapping descriptors, store mapping.
        List<MetadataMappingDescriptor> mappingResult = new ArrayList<>();
        for (String id : mappingDescriptorIds) {
            Optional<MetadataMappingDescriptor> mapping = mappingRegistry.getContribution(id);
            if (mapping.isEmpty()) {
                log.warn("Missing binary metadata descriptor with id: {}. "
                        + "Or check your rule contribution with proper metadataMapping-id.", id);
                continue;
            }
            mappingResult.add(mapping.get());
        }
        return mappingResult;
    }

    /**
     * Maps inspector only.
     */
    protected boolean isDirtyMapping(MetadataMappingDescriptor mappingDescriptor, DocumentModel doc) {
        Map<String, String> mappingResult = new HashMap<>();
        for (MetadataMappingDescriptor.MetadataDescriptor metadataDescriptor : mappingDescriptor.getMetadataDescriptors()) {
            mappingResult.put(metadataDescriptor.getXpath(), metadataDescriptor.getName());
        }
        // Returning only dirty properties
        HashMap<String, Object> resultDirtyMapping = new HashMap<>();
        for (String metadata : mappingResult.keySet()) {
            Property property = doc.getProperty(metadata);
            if (property.isDirty()) {
                resultDirtyMapping.put(mappingResult.get(metadata), doc.getPropertyValue(metadata));
            }
        }
        return !resultDirtyMapping.isEmpty();
    }
}
