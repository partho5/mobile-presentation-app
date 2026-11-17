package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

/** The contract file is damaged or is a version this build does not know. Never carries content. */
public final class ContractException extends Exception {

    public ContractException(String message) {
        super(message);
    }

    public ContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
