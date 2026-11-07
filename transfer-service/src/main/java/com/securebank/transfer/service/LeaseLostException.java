package com.securebank.transfer.service;

/** This worker no longer owns the transfer (its lease expired and another worker claimed it). */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String reference) {
        super("Lease lost for transfer " + reference);
    }
}
