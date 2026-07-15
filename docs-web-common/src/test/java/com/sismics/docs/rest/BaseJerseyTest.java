package com.sismics.docs.rest;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.docs.core.util.TransactionUtil;
import com.sismics.docs.rest.util.ClientUtil;
import com.sismics.util.filter.ApiKeyBasedSecurityFilter;
import com.sismics.util.filter.HeaderBasedSecurityFilter;
import com.sismics.util.filter.RequestContextFilter;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.glassfish.jersey.test.external.ExternalTestContainerFactory;
import org.glassfish.jersey.test.spi.TestContainerException;
import org.glassfish.jersey.test.spi.TestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
     * The deployed servlet context. Held so teardown can {@link WebappContext#undeploy()} it
     * SYNCHRONOUSLY, which destroys the filters (and thus shuts the AppContext singleton down) before
     * the next test starts — see {@link #tearDown()}.
     */
    private WebappContext webappContext;

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
     * HTTP port the Grizzly server is bound to. Assigned in {@link #setUp()} by reading the
     * real bound port back from the listener after {@code start()} — the server binds an
     * OS-assigned ephemeral port (port 0) and never reserves-then-releases one up front, so
     * there is no window in which another process/parallel suite can steal the port between
     * allocation and bind. Mirrors the GreenMail SMTP handling below.
     */
    private int httpPort;

    /**
     * SMTP port the GreenMail server is bound to. Assigned in {@link #setUp()} by reading the
     * real bound port back after start — never reserved up front, so there is no
     * reserve-then-release window in which another process can steal the port.
     */
    private int smtpPort;

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
        System.setProperty("docs.header_authentication", "true");
        // Trust the Grizzly loopback client so the header-auth integration test can authenticate.
        System.setProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY, "127.0.0.1,::1,0:0:0:0:0:0:0:1");

        // Bind the Grizzly server FIRST, on an OS-assigned ephemeral port (port 0), then read the
        // real bound port back from its listener. There is no reserve-then-release window: the port
        // is bound once and never handed out. The server MUST be started before super.setUp() runs,
        // because super.setUp() creates the external Jersey test container from getBaseUri() (which
        // reads httpPort) and captures that URI into the client — so httpPort has to hold the real
        // bound port by then.
        //
        // Built by hand rather than via HttpServer.createSimpleServer(docroot, host, port): that
        // overload wraps the int port in a PortRange, and PortRange rejects 0 ("Invalid range").
        // A NetworkListener constructed with int port 0 binds an OS-assigned port directly and
        // reports the real bound port after start(). This replicates createSimpleServer's setup
        // (a static handler for the docroot plus the "grizzly" listener).
        httpServer = new HttpServer();
        httpServer.getServerConfiguration().addHttpHandler(
                new StaticHttpHandler(getClass().getResource("/").getFile()), "/");
        NetworkListener listener = new NetworkListener("grizzly", "localhost", 0);
        httpServer.addListener(listener);
        WebappContext context = new WebappContext("GrizzlyContext", "/docs");
        webappContext = context;
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
        // Read the actual bound port back (Grizzly stores it on the listener after start), so
        // getBaseUri()/target() below resolve to the port the server is truly listening on.
        httpPort = listener.getPort();

        super.setUp();
        clientUtil = new ClientUtil(target());

        // Force the AppContext to start up NOW, on the setUp thread, so its one-time startup side effects
        // (the sismics_docs_font_mono*.ttf temp registerFonts leaks, the fresh RAM Lucene index) are
        // established BEFORE the test body's baseline snapshots — never lazily by the body's first request.
        // Paired with the synchronous undeploy in tearDown, this pins one deterministic AppContext per test.
        // Wrapped like the filter's own warm-up: AppContext.startUp() touches the DB, so it needs an
        // entity manager / transaction on this thread.
        TransactionUtil.handle(AppContext::getInstance);

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

    /**
     * Default upper bound for {@link #awaitCondition}. Generous so a slow CI host clears the
     * async-work window, yet the poll returns as soon as the condition holds, so a healthy run
     * pays only a few milliseconds.
     */
    private static final long AWAIT_TIMEOUT_MS = 10_000;

    /**
     * Poll interval for {@link #awaitCondition}.
     */
    private static final long AWAIT_POLL_INTERVAL_MS = 100;

    /**
     * A condition evaluated by {@link #awaitCondition} that is allowed to throw — the REST reads it
     * polls (a GET decoded into JSON or an image) declare checked exceptions.
     */
    @FunctionalInterface
    protected interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    /**
     * Bounded poll-until-ready: evaluate {@code condition} every {@link #AWAIT_POLL_INTERVAL_MS}
     * until it holds, returning as soon as it does, or fail with {@code message} once
     * {@link #AWAIT_TIMEOUT_MS} elapses. This replaces fixed sleeps around asynchronous work
     * (raster (re)generation, Lucene indexing) that completes on a background thread after the
     * triggering request returns: the assertion the caller cares about is preserved, but it is only
     * evaluated once the observable ready-state the async work produces is in place — so a fast host
     * proceeds immediately and a slow host waits just long enough, instead of racing a fixed delay.
     *
     * <p>An exception thrown by {@code condition} is treated as "not ready yet" and retried, so a
     * transient error during the async window (e.g. a 503 while a raster is mid-swap) does not fail
     * the test spuriously; the last exception is attached to the failure if the deadline passes.
     *
     * @param message   Failure message if the condition never holds within the deadline
     * @param condition The ready-state predicate (may throw; a throw is retried, not fatal)
     */
    protected static void awaitCondition(String message, ThrowingBooleanSupplier condition) throws InterruptedException {
        awaitCondition(() -> message, condition);
    }

    /**
     * As {@link #awaitCondition(String, ThrowingBooleanSupplier)}, but the failure message is supplied
     * lazily so it can report the LAST observed state (e.g. the top suggestion actually returned) —
     * evaluated only if the deadline passes.
     *
     * @param message   Supplies the failure message, evaluated only on timeout
     * @param condition The ready-state predicate (may throw; a throw is retried, not fatal)
     */
    protected static void awaitCondition(java.util.function.Supplier<String> message,
                                         ThrowingBooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT_MS * 1_000_000L;
        Exception lastException = null;
        while (true) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                lastException = null;
            } catch (Exception e) {
                lastException = e;
            }
            if (System.nanoTime() >= deadline) {
                Assertions.fail(message.get() + " (waited " + AWAIT_TIMEOUT_MS + "ms)", lastException);
                return;
            }
            Thread.sleep(AWAIT_POLL_INTERVAL_MS);
        }
    }

    /**
     * Upper bound for {@link #awaitProcessingQuiescence()}. Generous so heavy content extraction
     * (OCR, PDF/office rasterization) on a slow CI host finishes, yet the poll returns the instant the
     * processing set drains, so a healthy run pays only milliseconds.
     */
    private static final long QUIESCENCE_TIMEOUT_MS = 30_000;

    /**
     * Block until asynchronous file processing has drained — no file remains marked in-flight in
     * {@link FileUtil}'s process-wide processing set.
     *
     * <p>File content extraction and thumbnail/raster generation run <em>after</em> the triggering
     * request has already returned to the client (the request commits, then the queued file-processing
     * event is dispatched on a worker). That post-response work writes {@code sismics_docs} temp files
     * into the shared system tmpdir and mutates the Lucene index — global resources that other tests
     * observe. A test that snapshots those resources, and this base class's per-test teardown, must
     * first let any in-flight processing finish, otherwise a straggler from this (or an immediately
     * preceding) test races the observation and produces a spurious, order-dependent failure. Returns
     * as soon as the set is empty; on a genuinely stuck processor it stops waiting after
     * {@link #QUIESCENCE_TIMEOUT_MS} and lets the caller proceed (best-effort — never fails a test).</p>
     */
    protected static void awaitProcessingQuiescence() throws InterruptedException {
        long deadline = System.nanoTime() + QUIESCENCE_TIMEOUT_MS * 1_000_000L;
        while (FileUtil.getProcessingFileCount() != 0) {
            if (System.nanoTime() >= deadline) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Override
    @AfterEach
    public void tearDown() throws Exception {
        // Drain any in-flight post-response file processing BEFORE tearing the server down, so a
        // straggler cannot cross into the next test and pollute its view of the shared tmpdir / index.
        awaitProcessingQuiescence();
        super.tearDown();
        System.clearProperty("docs.header_authentication");
        System.clearProperty(HeaderBasedSecurityFilter.TRUSTED_PROXIES_PROPERTY);
        if (greenMail != null) {
            greenMail.stop();
        }
        // Undeploy the servlet context SYNCHRONOUSLY first: this destroys the filters (RequestContextFilter
        // .destroy() -> AppContext.shutDown()) on THIS thread, before the next test begins. httpServer
        // .shutdownNow() alone destroys the filters asynchronously, so the AppContext singleton could be
        // nulled LATE — after the next test's warm-up had already re-created it — making that test's first
        // request lazily re-run AppContext.startUp() mid-body. That late startup leaks a
        // sismics_docs_font_mono*.ttf temp (registerFonts never deletes it) into a later test's temp-leak
        // delta AND swaps in a fresh empty RAM Lucene index that drops a document indexed earlier in the
        // same test — the shared root cause of the order-dependent observation flakes.
        if (webappContext != null) {
            try {
                webappContext.undeploy();
            } catch (RuntimeException e) {
                // Best-effort: an undeploy failure must not mask the test outcome.
            }
        }
        if (httpServer != null) {
            httpServer.shutdownNow();
        }
    }
}
