/*
 * (C) Copyright 2007-2013 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Max Stepanov
 *     Florent Guillaume
 */
package org.nuxeo.ecm.platform.picture;

import static org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants.CONVERSION_FORMAT;
import static org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants.JPEG_CONVERSATION_FORMAT;
import static org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants.OPERATION_RESIZE;
import static org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants.OPTION_RESIZE_DEPTH;
import static org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants.OPTION_RESIZE_HEIGHT;
import static org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants.OPTION_RESIZE_WIDTH;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.el.ExpressionFactoryImpl;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.util.Properties;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.blobholder.SimpleBlobHolder;
import org.nuxeo.ecm.core.api.impl.blob.BlobWrapper;
import org.nuxeo.ecm.core.convert.api.ConversionService;
import org.nuxeo.ecm.platform.actions.ActionContext;
import org.nuxeo.ecm.platform.actions.ELActionContext;
import org.nuxeo.ecm.platform.actions.ejb.ActionManager;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandException;
import org.nuxeo.ecm.platform.commandline.executor.api.CommandNotAvailable;
import org.nuxeo.ecm.platform.el.ExpressionContext;
import org.nuxeo.ecm.platform.mimetype.MimetypeDetectionException;
import org.nuxeo.ecm.platform.mimetype.MimetypeNotFoundException;
import org.nuxeo.ecm.platform.mimetype.interfaces.MimetypeRegistry;
import org.nuxeo.ecm.platform.picture.api.ImageInfo;
import org.nuxeo.ecm.platform.picture.api.ImagingConfigurationDescriptor;
import org.nuxeo.ecm.platform.picture.api.ImagingService;
import org.nuxeo.ecm.platform.picture.api.PictureConversion;
import org.nuxeo.ecm.platform.picture.api.PictureView;
import org.nuxeo.ecm.platform.picture.api.PictureViewImpl;
import org.nuxeo.ecm.platform.picture.core.libraryselector.LibrarySelector;
import org.nuxeo.ecm.platform.picture.magick.utils.ImageIdentifier;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

public class ImagingComponent extends DefaultComponent implements ImagingService {

    private static final Log log = LogFactory.getLog(ImagingComponent.class);

    public static final String CONFIGURATION_PARAMETERS_EP = "configuration";

    public static final String PICTURE_CONVERSIONS_EP = "pictureConversions";

    protected Map<String, String> configurationParameters = new HashMap<>();

    protected PictureConversionRegistry pictureConversionRegistry = new PictureConversionRegistry();

    private LibrarySelector librarySelector;

    protected final PictureMigrationHandler pictureMigrationHandler = new PictureMigrationHandler();

    @Override
    public List<PictureConversion> getPictureConversions() {
        return pictureConversionRegistry.getPictureConversions();
    }

    @Override
    public PictureConversion getPictureConversion(String id) {
        return pictureConversionRegistry.getPictureConversion(id);
    }

    @Override
    public Blob crop(Blob blob, int x, int y, int width, int height) {
        return getLibrarySelectorService().getImageUtils().crop(blob, x, y, width, height);
    }

    @Override
    public Blob resize(Blob blob, String finalFormat, int width, int height, int depth) {
        return getLibrarySelectorService().getImageUtils().resize(blob, finalFormat, width, height, depth);
    }

    @Override
    public Blob rotate(Blob blob, int angle) {
        return getLibrarySelectorService().getImageUtils().rotate(blob, angle);
    }

    @Override
    public Map<String, Object> getImageMetadata(Blob blob) {
        log.warn("org.nuxeo.ecm.platform.picture.ImagingComponent.getImageMetadata is deprecated. Please use "
                + "org.nuxeo.binary.metadata.api.BinaryMetadataService#readMetadata(org.nuxeo.ecm.core.api.Blob)");
        return Collections.emptyMap();
    }

    @Override
    public String getImageMimeType(File file) {
        try {
            MimetypeRegistry mimetypeRegistry = Framework.getLocalService(MimetypeRegistry.class);
            if (file.getName() != null) {
                return mimetypeRegistry.getMimetypeFromFilenameAndBlobWithDefault(file.getName(), Blobs.createBlob(file),
                        "image/jpeg");
            } else {
                return mimetypeRegistry.getMimetypeFromFile(file);
            }
        } catch (MimetypeNotFoundException | MimetypeDetectionException | IOException e) {
            log.error("Unable to retrieve mime type", e);
        }
        return null;
    }

    @Override
    public String getImageMimeType(Blob blob) {
        try {
            MimetypeRegistry mimetypeRegistry = Framework.getLocalService(MimetypeRegistry.class);
            if (blob.getFilename() != null) {
                return mimetypeRegistry.getMimetypeFromFilenameAndBlobWithDefault(blob.getFilename(), blob,
                        "image/jpeg");
            } else {
                return mimetypeRegistry.getMimetypeFromBlob(blob);
            }
        } catch (MimetypeNotFoundException | MimetypeDetectionException e) {
            log.error("Unable to retrieve mime type", e);
        }
        return null;
    }

    private LibrarySelector getLibrarySelectorService() {
        if (librarySelector == null) {
            librarySelector = Framework.getRuntime().getService(LibrarySelector.class);
        }
        if (librarySelector == null) {
            log.error("Unable to get LibrarySelector runtime service");
            throw new NuxeoException("Unable to get LibrarySelector runtime service");
        }
        return librarySelector;
    }

    @Override
    public ImageInfo getImageInfo(Blob blob) {
        ImageInfo imageInfo = null;
        try {
            String ext = blob.getFilename() == null ? ".tmp" : "." + FilenameUtils.getExtension(blob.getFilename());
            try (CloseableFile cf = blob.getCloseableFile(ext)) {
                imageInfo = ImageIdentifier.getInfo(cf.getFile().getCanonicalPath());
            }
        } catch (CommandNotAvailable | CommandException e) {
            log.error("Failed to get ImageInfo for file " + blob.getFilename(), e);
        } catch (IOException e) {
            log.error("Failed to transfer file " + blob.getFilename(), e);
        }
        return imageInfo;
    }

    @Override
    public void registerContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        if (CONFIGURATION_PARAMETERS_EP.equals(extensionPoint)) {
            ImagingConfigurationDescriptor desc = (ImagingConfigurationDescriptor) contribution;
            configurationParameters.putAll(desc.getParameters());
        } else if (PICTURE_CONVERSIONS_EP.equals(extensionPoint)) {
            pictureConversionRegistry.addContribution((PictureConversion) contribution);
        }
    }

    @Override
    public void unregisterContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        if (CONFIGURATION_PARAMETERS_EP.equals(extensionPoint)) {
            ImagingConfigurationDescriptor desc = (ImagingConfigurationDescriptor) contribution;
            for (String configuration : desc.getParameters().keySet()) {
                configurationParameters.remove(configuration);
            }
        } else if (PICTURE_CONVERSIONS_EP.equals(extensionPoint)) {
            pictureConversionRegistry.removeContribution((PictureConversion) contribution);
        }
    }

    @Override
    public String getConfigurationValue(String configurationName) {
        return configurationParameters.get(configurationName);
    }

    @Override
    public String getConfigurationValue(String configurationName, String defaultValue) {
        return configurationParameters.containsKey(configurationName) ? configurationParameters.get(configurationName)
                : defaultValue;
    }

    @Override
    public void setConfigurationValue(String configurationName, String configurationValue) {
        configurationParameters.put(configurationName, configurationValue);
    }

    @Override
    public PictureView computeViewFor(Blob blob, PictureConversion pictureConversion, boolean convert)
            throws IOException {
        return computeViewFor(blob, pictureConversion, null, convert);
    }

    @Override
    public PictureView computeViewFor(Blob blob, PictureConversion pictureConversion, ImageInfo imageInfo,
            boolean convert) throws IOException {
        String mimeType = blob.getMimeType();
        if (mimeType == null) {
            blob.setMimeType(getImageMimeType(blob));
        }

        if (imageInfo == null) {
            imageInfo = getImageInfo(blob);
        }
        return computeView(blob, pictureConversion, imageInfo, convert);
    }

    @Override
    public List<PictureView> computeViewsFor(Blob blob, List<PictureConversion> pictureConversions, boolean convert)
            throws IOException {
        return computeViewsFor(blob, pictureConversions, null, convert);
    }

    @Override
    public List<PictureView> computeViewsFor(Blob blob, List<PictureConversion> pictureConversions,
            ImageInfo imageInfo, boolean convert) throws IOException {
        String mimeType = blob.getMimeType();
        if (mimeType == null) {
            blob.setMimeType(getImageMimeType(blob));
        }

        if (imageInfo == null) {
            imageInfo = getImageInfo(blob);
        }
        List<PictureView> views = new ArrayList<PictureView>();
        for (PictureConversion pictureConversion : pictureConversions) {
            views.add(computeView(blob, pictureConversion, imageInfo, convert));
        }
        return views;
    }

    protected PictureView computeView(Blob blob, PictureConversion pictureConversion, ImageInfo imageInfo,
            boolean convert) throws IOException {
        return computeView(null, blob, pictureConversion, imageInfo, convert);
    }

    protected PictureView computeView(DocumentModel doc, Blob blob, PictureConversion pictureConversion,
            ImageInfo imageInfo, boolean convert) throws IOException {
        if (convert) {
            return computeView(doc, blob, pictureConversion, imageInfo);
        } else {
            return computeViewWithoutConversion(blob, pictureConversion, imageInfo);
        }
    }

    /**
     * Use
     * {@link ImagingComponent#computeView(org.nuxeo.ecm.core.api.DocumentModel, Blob, org.nuxeo.ecm.platform.picture.api.PictureConversion, ImageInfo)}
     * by passing the <b>Original</b> picture template.
     *
     * @deprecated since 7.1
     */
    @Deprecated
    protected PictureView computeOriginalView(Blob blob, PictureConversion pictureConversion, ImageInfo imageInfo)
            throws IOException {
        String filename = blob.getFilename();
        String title = pictureConversion.getId();
        String viewFilename = title + "_" + filename;
        Map<String, Serializable> map = new HashMap<String, Serializable>();
        map.put(PictureView.FIELD_TITLE, pictureConversion.getId());
        map.put(PictureView.FIELD_DESCRIPTION, pictureConversion.getDescription());
        map.put(PictureView.FIELD_FILENAME, viewFilename);
        map.put(PictureView.FIELD_TAG, pictureConversion.getTag());
        map.put(PictureView.FIELD_WIDTH, imageInfo.getWidth());
        map.put(PictureView.FIELD_HEIGHT, imageInfo.getHeight());

        Blob originalViewBlob = wrapBlob(blob);
        originalViewBlob.setFilename(viewFilename);
        map.put(PictureView.FIELD_CONTENT, (Serializable) originalViewBlob);
        map.put(PictureView.FIELD_INFO, imageInfo);
        return new PictureViewImpl(map);
    }

    protected Blob wrapBlob(Blob blob) {
        return new BlobWrapper(blob);
    }

    /**
     * Use
     * {@link ImagingComponent#computeView(org.nuxeo.ecm.core.api.DocumentModel, Blob, org.nuxeo.ecm.platform.picture.api.PictureConversion, ImageInfo)}
     * by passing the <b>OriginalJpeg</b> picture template.
     *
     * @deprecated since 7.1
     */
    @Deprecated
    protected PictureView computeOriginalJpegView(Blob blob, PictureConversion pictureConversion, ImageInfo imageInfo)
            throws IOException {
        String filename = blob.getFilename();
        String title = pictureConversion.getId();
        int width = imageInfo.getWidth();
        int height = imageInfo.getHeight();
        Map<String, Serializable> map = new HashMap<String, Serializable>();
        map.put(PictureView.FIELD_TITLE, pictureConversion.getId());
        map.put(PictureView.FIELD_DESCRIPTION, pictureConversion.getDescription());
        map.put(PictureView.FIELD_TAG, pictureConversion.getTag());
        map.put(PictureView.FIELD_WIDTH, width);
        map.put(PictureView.FIELD_HEIGHT, height);
        Map<String, Serializable> options = new HashMap<String, Serializable>();
        options.put(OPTION_RESIZE_WIDTH, width);
        options.put(OPTION_RESIZE_HEIGHT, height);
        options.put(OPTION_RESIZE_DEPTH, imageInfo.getDepth());
        // always convert to jpeg
        options.put(CONVERSION_FORMAT, JPEG_CONVERSATION_FORMAT);
        BlobHolder bh = new SimpleBlobHolder(blob);
        ConversionService conversionService = Framework.getLocalService(ConversionService.class);
        bh = conversionService.convert(OPERATION_RESIZE, bh, options);

        Blob originalJpegBlob = bh.getBlob();
        if (originalJpegBlob == null) {
            originalJpegBlob = wrapBlob(blob);
        }
        String viewFilename = String.format("%s_%s.%s", title, FilenameUtils.getBaseName(blob.getFilename()),
                FilenameUtils.getExtension(JPEG_CONVERSATION_FORMAT));
        map.put(PictureView.FIELD_FILENAME, viewFilename);
        originalJpegBlob.setFilename(viewFilename);
        map.put(PictureView.FIELD_CONTENT, (Serializable) originalJpegBlob);
        map.put(PictureView.FIELD_INFO, getImageInfo(originalJpegBlob));
        return new PictureViewImpl(map);
    }

    /**
     * @deprecated since 7.1. We now use the original Blob base name + the computed Blob filename extension.
     */
    @Deprecated
    protected String computeViewFilename(String filename, String format) {
        int index = filename.lastIndexOf(".");
        if (index == -1) {
            return filename + "." + format;
        } else {
            return filename.substring(0, index + 1) + format;
        }
    }

    protected PictureView computeView(DocumentModel doc, Blob blob, PictureConversion pictureConversion,
            ImageInfo imageInfo) {

        String title = pictureConversion.getId();

        Map<String, Serializable> pictureViewMap = new HashMap<String, Serializable>();
        pictureViewMap.put(PictureView.FIELD_TITLE, title);
        pictureViewMap.put(PictureView.FIELD_DESCRIPTION, pictureConversion.getDescription());
        pictureViewMap.put(PictureView.FIELD_TAG, pictureConversion.getTag());

        Point size = new Point(imageInfo.getWidth(), imageInfo.getHeight());

        /*
         * If the picture template have a max size then use it for the new size computation, else take the current size
         * will be used.
         */
        if (pictureConversion.getMaxSize() != null) {
            size = getSize(size, pictureConversion.getMaxSize());
        }

        pictureViewMap.put(PictureView.FIELD_WIDTH, size.x);
        pictureViewMap.put(PictureView.FIELD_HEIGHT, size.y);

        // Use the registered conversion format
        String conversionFormat = getConfigurationValue(CONVERSION_FORMAT, JPEG_CONVERSATION_FORMAT);

        Blob viewBlob = callPictureConversionChain(doc, blob, pictureConversion, imageInfo, size, conversionFormat);

        String viewFilename = String.format("%s_%s.%s", title, FilenameUtils.getBaseName(blob.getFilename()),
                FilenameUtils.getExtension(viewBlob.getFilename()));
        viewBlob.setFilename(viewFilename);
        pictureViewMap.put(PictureView.FIELD_FILENAME, viewFilename);
        pictureViewMap.put(PictureView.FIELD_CONTENT, (Serializable) viewBlob);
        pictureViewMap.put(PictureView.FIELD_INFO, getImageInfo(viewBlob));

        return new PictureViewImpl(pictureViewMap);
    }

    protected Blob callPictureConversionChain(DocumentModel doc, Blob blob, PictureConversion pictureConversion,
            ImageInfo imageInfo, Point size, String conversionFormat) {
        String chainId = pictureConversion.getChainId();

        // if the chainId is null just use the same blob (wrapped)
        if (StringUtils.isBlank(chainId)) {
            return wrapBlob(blob);
        }

        Properties parameters = new Properties();
        parameters.put(OPTION_RESIZE_WIDTH, String.valueOf(size.x));
        parameters.put(OPTION_RESIZE_HEIGHT, String.valueOf(size.y));
        parameters.put(OPTION_RESIZE_DEPTH, String.valueOf(imageInfo.getDepth()));
        parameters.put(CONVERSION_FORMAT, conversionFormat);

        Map<String, Object> chainParameters = new HashMap<>();
        chainParameters.put("parameters", parameters);

        OperationContext context = new OperationContext();
        if (doc != null) {
            DocumentModel pictureDocument = doc.getCoreSession().getDocument(doc.getRef());
            pictureDocument.detach(true);
            context.put("pictureDocument", pictureDocument);
        }
        context.setInput(blob);

        boolean txWasActive = false;
        try {
            if (TransactionHelper.isTransactionActive()) {
                txWasActive = true;
                TransactionHelper.commitOrRollbackTransaction();
            }

            Blob viewBlob = (Blob) Framework.getService(AutomationService.class).run(context, chainId, chainParameters);
            if (viewBlob == null) {
                viewBlob = wrapBlob(blob);
            }
            return viewBlob;
        } catch (OperationException e) {
            throw new NuxeoException(e);
        } finally {
            if (txWasActive && !TransactionHelper.isTransactionActiveOrMarkedRollback()) {
                TransactionHelper.startTransaction();
            }
        }
    }

    @Override
    public List<PictureView> computeViewsFor(DocumentModel doc, Blob blob, ImageInfo imageInfo, boolean convert)
            throws IOException {
        List<PictureConversion> pictureConversions = getPictureConversions();
        List<PictureView> pictureViews = new ArrayList<>(pictureConversions.size());

        for (PictureConversion pictureConversion : pictureConversions) {
            if (canApplyPictureConversion(pictureConversion, doc)) {
                PictureView pictureView = computeView(doc, blob, pictureConversion, imageInfo, convert);
                pictureViews.add(pictureView);
            }
        }

        return pictureViews;
    }

    protected boolean canApplyPictureConversion(PictureConversion pictureConversion, DocumentModel doc) {
        if (pictureConversion.isDefault()) {
            return true;
        }

        ActionManager actionService = Framework.getService(ActionManager.class);
        return actionService.checkFilters(pictureConversion.getFilterIds(), createActionContext(doc));
    }

    protected ActionContext createActionContext(DocumentModel doc) {
        ActionContext actionContext = new ELActionContext(new ExpressionContext(), new ExpressionFactoryImpl());
        actionContext.setCurrentDocument(doc);
        return actionContext;
    }

    protected PictureView computeViewWithoutConversion(Blob blob, PictureConversion pictureConversion,
            ImageInfo imageInfo) {
        PictureView view = new PictureViewImpl();
        view.setBlob(blob);
        view.setWidth(imageInfo.getWidth());
        view.setHeight(imageInfo.getHeight());
        view.setFilename(blob.getFilename());
        view.setTitle(pictureConversion.getId());
        view.setDescription(pictureConversion.getDescription());
        view.setTag(pictureConversion.getTag());
        view.setImageInfo(imageInfo);
        return view;
    }

    protected static Point getSize(Point current, int max) {
        int x = current.x;
        int y = current.y;
        int newX;
        int newY;
        if (x > y) { // landscape
            newY = (y * max) / x;
            newX = max;
        } else { // portrait
            newX = (x * max) / y;
            newY = max;
        }
        if (newX > x || newY > y) {
            return current;
        }
        return new Point(newX, newY);
    }

    @Override
    public List<List<PictureView>> computeViewsFor(List<Blob> blobs, List<PictureConversion> pictureConversions,
            boolean convert) throws IOException {
        return computeViewsFor(blobs, pictureConversions, null, convert);
    }

    @Override
    public List<List<PictureView>> computeViewsFor(List<Blob> blobs, List<PictureConversion> pictureConversions,
            ImageInfo imageInfo, boolean convert) throws IOException {
        List<List<PictureView>> allViews = new ArrayList<List<PictureView>>();
        for (Blob blob : blobs) {
            allViews.add(computeViewsFor(blob, pictureConversions, imageInfo, convert));
        }
        return allViews;
    }

    @Override
    public void activate(ComponentContext context) {
        pictureMigrationHandler.install();
    }

    @Override
    public void deactivate(ComponentContext context) {
        pictureMigrationHandler.uninstall();
    }
}