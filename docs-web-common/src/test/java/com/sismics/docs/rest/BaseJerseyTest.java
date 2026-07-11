package com.sismics.docs.rest;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.sismics.docs.rest.util.ClientUtil;
import com.sismics.util.filter.ApiKeyBasedSecurityFilter;
import com.sismics.util.filter.HeaderBasedSecurityFilter;
import com.sismics.util.filter.RequestContextFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.external.ExternalTestContainerFactory;
import org.glassfish.jersey.test.spi.TestContainerException;
import org.glassfish.jersey.test.spi.TestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Base class of integration tests with Jersey.
 * 
 * @author jtremeaux
 */
public abstract class BaseJerseyTest extends JerseyTest {
    protected static final String FILE_APACHE_PPTX = "file/apache.pptx";
    protected static final String FILE_DOCUMENT_DOCX = "file/document.docx";
    protected static final String FILE_DOCUMENT_ODT = "file/document.odt";
    protected static final String FILE_DOCUMENT_TXT = "file/document.txt";
    protected static final String FILE_EINSTEIN_ROOSEVELT_LETTER_PNG = "file/Einstein-Roosevelt-letter.png";
    protected static final long FILE_EINSTEIN_ROOSEVELT_LETTER_PNG_SIZE = 292641L;
    protected static final String FILE_PIA_00452_JPG = "file/PIA00452.jpg";
    protected static final long FILE_PIA_00452_JPG_SIZE = 163510L;
    protected static final String FILE_VIDEO_WEBM = "file/video.webm";
    protected static final String FILE_WIKIPEDIA_PDF = "file/wikipedia.pdf";
    protected static final String FILE_WIKIPEDIA_ZIP = "file/wikipedia.zip";
    protected static final String FILE_XSS_HTML = "file/xss.html";

    /**
     * Test HTTP server.
     */
    private HttpServer httpServer;
    
    /**
     * Utility class for the REST client.
     */
    protected ClientUtil clientUtil;

    /**
     * Test mail server.
     */
    private GreenMail greenMail;

    /**
     * Store indices of the messages already consumed by {@link #popEmail()} — GreenMail's
     * {@code getReceivedMessages()} is non-destructive, so consumption is tracked here.
     */
    private final Set<Integer> consumedEmailIndices = new HashSet<>();

    /**
     * Upper bound for waiting on GreenMail's asynchronous delivery to its message store.
     */
    private static final long EMAIL_DELIVERY_TIMEOUT_MS = 5000;

    /**
     * HTTP port for the Grizzly server, assigned by the OS (ephemeral) so parallel
     * test suites on the same host do not collide on a fixed port.
     */
    private final int httpPort = findFreePort();

    /**
     * SMTP port the GreenMail server is bound to. Assigned in {@link #setUp()} by reading the
     * real bound port back after start — never reserved up front, so there is no
     * reserve-then-release window in which another process can steal the port.
     */
    private int smtpPort;

    /**
     * Find a free TCP port by binding to port 0 and letting the OS assign one.
     *
     * @return An available port number
     */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to allocate a free port for the test server", e);
        }
    }

    /**
     * Returns the SMTP port the embedded GreenMail mail server is listening on. Mail-sending tests
     * must propagate this into the app's SMTP configuration so the app connects to the test server.
     *
     * @return The GreenMail SMTP port
     */
    protected int getSmtpPort() {
        return smtpPort;
    }

    public String adminToken() {
        return clientUtil.login("admin", "admin", false);
    }

    @Override
    protected TestContainerFactory getTestContainerFactory() throws TestContainerException {
        return new ExternalTestContainerFactory();
    }

    @Override
    protected Application configure() {
        String travisEnv = System.getenv("TRAVIS");
        if (!Objects.equals(travisEnv, "true")) {
            // Travis doesn't like big logs
            enable(TestProperties.LOG_TRAFFIC);
            enable(TestProperties.DUMP_ENTITY);
        }
        return new Application();
    }
    
    @Override
    protected URI getBaseUri() {
        // Build the base URI directly from the ephemeral port we reserved, rather than delegating
        // to super.getBaseUri() whose port resolution (property vs. external container) is fragile.
        return UriBuilder.fromUri("http://localhost/").port(httpPort).path("docs").build();
    }
    
    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        System.setProperty("docs.header_authentication", "true");
        // Trust the Grizzly loopback client so the header-auth integration test can authenticate.
        System.setProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY, "127.0.0.1,::1,0:0:0:0:0:0:0:1");

        clientUtil = new ClientUtil(target());

        httpServer = HttpServer.createSimpleServer(getClass().getResource("/").getFile(), "localhost", httpPort);
        WebappContext context = new WebappContext("GrizzlyContext", "/docs");
        context.addListener("com.sismics.util.listener.IIOProviderContextListener");
        context.addFilter("requestContextFilter", RequestContextFilter.class)
                .addMappingForUrlPatterns(null, "/*");
        context.addFilter("tokenBasedSecurityFilter", TokenBasedSecurityFilter.class)
                .addMappingForUrlPatterns(null, "/*");
        context.addFilter("apiKeyBasedSecurityFilter", ApiKeyBasedSecurityFilter.class)
                .addMappingForUrlPatterns(null, "/*");
        context.addFilter("headerBasedSecurityFilter", HeaderBasedSecurityFilter.class)
                .addMappingForUrlPatterns(null, "/*");
        ServletRegistration reg = context.addServlet("jerseyServlet", ServletContainer.class);
        reg.setInitParameter("jersey.config.server.provider.packages", "com.sismics.docs.rest.resource");
        reg.setInitParameter("jersey.config.server.provider.classnames", "org.glassfish.jersey.media.multipart.MultiPartFeature");
        reg.setInitParameter("jersey.config.server.response.setStatusOverSendError", "true");
        reg.setLoadOnStartup(1);
        reg.addMapping("/*");
        reg.setAsyncSupported(true);
        context.deploy(httpServer);
        httpServer.start();

        consumedEmailIndices.clear();
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        // Read the real bound port back only after start; injection into the app's SMTP
        // config (via getSmtpPort()) can therefore never race another port consumer.
        smtpPort = greenMail.getSmtp().getPort();
    }

    /**
     * Extract the newest unconsumed email and consume it. Returns null when there is none.
     *
     * @return Email content
     * @throws MessagingException e
     * @throws IOException e
     */
    protected String popEmail() throws MessagingException, IOException {
        // GreenMail acknowledges SMTP DATA before the message reaches its store — a bounded
        // wait keeps a just-sent email visible without racing the server thread.
        greenMail.waitForIncomingEmail(EMAIL_DELIVERY_TIMEOUT_MS, consumedEmailIndices.size() + 1);
        MimeMessage[] messages = greenMail.getReceivedMessages();
        for (int i = messages.length - 1; i >= 0; i--) {
            if (consumedEmailIndices.add(i)) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                messages[i].writeTo(os);
                return os.toString();
            }
        }
        return null;
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        super.tearDown();
        System.clearProperty("docs.header_authentication");
        System.clearProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY);
        if (greenMail != null) {
            greenMail.stop();
        }
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }
}
