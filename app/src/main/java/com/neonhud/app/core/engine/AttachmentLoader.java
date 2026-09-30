package com.neonhud.app.core.engine;

/** Turns an attached file into something a model can read. Blocking; called from the chat worker thread. */
public interface AttachmentLoader {
    /** @return the same attachment with its text / images filled in. Throws if the file cannot be read. */
    Attachment load(Attachment attachment) throws Exception;
}
