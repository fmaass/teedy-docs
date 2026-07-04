package com.sismics.util;

import com.sismics.docs.core.util.authentication.AuthenticationHandler;
import com.sismics.docs.core.util.authentication.InternalAuthenticationHandler;
import com.sismics.docs.core.util.format.FormatHandler;
import com.sismics.docs.core.util.indexing.IndexingHandler;
import com.sismics.docs.core.util.indexing.LuceneIndexingHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class TestClasspathScanner {

    @Test
    public void authenticationHandlerDiscovery() {
        List<Class<AuthenticationHandler>> handlers = new ClasspathScanner<AuthenticationHandler>()
                .findClasses(AuthenticationHandler.class, "com.sismics.docs.core.util.authentication");
        // After LDAP retirement the internal handler must still be resolved by the ServiceLoader,
        // otherwise username/password login would break.
        Assertions.assertFalse(handlers.isEmpty(), "Expected at least 1 auth handler, got " + handlers.size());
        Assertions.assertTrue(handlers.contains(InternalAuthenticationHandler.class));
    }

    @Test
    public void indexingHandlerDiscovery() {
        List<Class<IndexingHandler>> handlers = new ClasspathScanner<IndexingHandler>()
                .findClasses(IndexingHandler.class, "com.sismics.docs.core.util.indexing");
        Assertions.assertFalse(handlers.isEmpty(), "Expected at least 1 indexing handler");
        Assertions.assertTrue(handlers.contains(LuceneIndexingHandler.class));
    }

    @Test
    public void formatHandlerDiscovery() {
        List<Class<FormatHandler>> handlers = new ClasspathScanner<FormatHandler>()
                .findClasses(FormatHandler.class, "com.sismics.docs.core.util.format");
        Assertions.assertTrue(handlers.size() >= 7, "Expected at least 7 format handlers, got " + handlers.size());
    }
}
