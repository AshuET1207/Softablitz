package com.slingring.net;

import com.slingring.model.Decision;
import com.slingring.model.TransferState;

/**
 * JSON bodies exchanged between peers. Kept in one place so the server and the
 * client can never disagree about field names.
 */
public final class Wire {

    private Wire() { }

    /** GET /peer/info response. The caller already knows the IP and port it used. */
    public record Info(String id, String name) { }

    /** POST /peer/request response. */
    public record DecisionBody(Decision decision) { }

    /** PUT /peer/data/{id} response: how the receiver ended up (INTACT, CORRUPTED, CANCELLED, FAILED). */
    public record StateBody(TransferState state) { }

    /** Any non-2xx response. */
    public record ErrorBody(String error) { }
}