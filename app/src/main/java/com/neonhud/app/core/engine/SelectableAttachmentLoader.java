package com.neonhud.app.core.engine;

/** Optional extension for loaders that can read only an explicit page/slide/sheet selection. */
public interface SelectableAttachmentLoader extends AttachmentLoader {
    Attachment load(Attachment attachment, ReadSelection selection) throws Exception;

    @Override default Attachment load(Attachment attachment) throws Exception {
        return load(attachment, ReadSelection.none());
    }
}
