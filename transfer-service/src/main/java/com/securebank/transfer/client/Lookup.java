package com.securebank.transfer.client;

/** Result of a status check ("was my earlier request applied?"). */
public sealed interface Lookup<T> {

    record Found<T>(T value) implements Lookup<T> {
    }

    record NotFound<T>() implements Lookup<T> {
    }

    /** The check itself could not be completed; nothing can be concluded. */
    record Unavailable<T>(String reason) implements Lookup<T> {
    }
}
