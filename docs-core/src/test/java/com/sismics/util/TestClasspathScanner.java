package com.sismics.util;

import com.sismics.docs.core.util.authentication.AuthenticationHandler;
import com.sismics.docs.core.util.authentication.InternalAuthenticationHandler;
import com.sismics.docs.core.util.authentication.LdapAuthenticationHandler;
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
        // Both auth handlers must be resolved by the ServiceLoader: the internal handler
        // (username/password login) and the LDAP handler. A missing entry — or a stale
        // descriptor line for a class that no longer exists — throws ServiceConfigurationError
        // at auth init and breaks ALL login, so this guards the whole login chain.
        Assertions.assertFalse(handlers.isEmpty(), "Expected at least 2 auth handlers, got " + handlers.size());
        Assertions.assertTrue(handlers.contains(InternalAuthenticationHandler.class));
        Assertions.assertTrue(handlers.contains(LdapAuthenticationHandler.class));
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
