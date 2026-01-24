package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.SessionInfo;
import lombok.Builder;
import lombok.Value;
import org.openjproxy.grpc.server.action.ActionContext;

/**
 * Request DTO for {@link SessionConnectionAction}.
 * Holds the context and session information needed to acquire a database
 * connection.
 */
@Value
@Builder
public class SessionConnectionRequest {
    /**
     * Shared action context containing managers and state maps.
     */
    ActionContext context;

    /**
     * Session information from the client request.
     */
    SessionInfo sessionInfo;

    /**
     * Flag to indicate if a new session should be started if none exists.
     */
    boolean startSessionIfNone;
}
