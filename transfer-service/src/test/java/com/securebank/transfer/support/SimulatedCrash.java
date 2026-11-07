package com.securebank.transfer.support;

/** An Error (not an Exception) so it escapes the saga's error handling, like a real JVM crash. */
public class SimulatedCrash extends Error {

    public SimulatedCrash(String where) {
        super("Simulated crash after " + where);
    }
}
