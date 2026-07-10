package com.sismics.docs.core.listener.async;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.dto.UserDto;
import com.sismics.docs.core.event.RouteStepRejectedEvent;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.util.EmailUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Listener for route step rejection: emails the rejection notice to the route initiator.
 *
 * @author bgamard
 */
public class RouteStepRejectedAsyncListener {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(RouteStepRejectedAsyncListener.class);

    /**
     * Handle events.
     *
     * @param event Event
     */
    @Subscribe
    @AllowConcurrentEvents
    public void on(final RouteStepRejectedEvent event) {
        if (log.isInfoEnabled()) {
            log.info("Route step rejected event: " + event.toString());
        }

        TransactionUtil.handle(() -> {
            final UserDto user = event.getUser();

            // Send the rejection notice email
            Map<String, Object> paramRootMap = new HashMap<>();
            paramRootMap.put("user_name", user.getUsername());
            paramRootMap.put("document_id", event.getDocument().getId());
            paramRootMap.put("document_title", event.getDocument().getTitle());
            paramRootMap.put("step_name", event.getStepName());
            if (event.getComment() != null) {
                // Only present when the rejecting actor left a comment; the template guards on it
                paramRootMap.put("step_comment", event.getComment());
            }

            EmailUtil.sendEmail(Constants.EMAIL_TEMPLATE_ROUTE_STEP_REJECTED, user, paramRootMap);
        });
    }
}
