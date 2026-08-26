package com.sismics.docs.rest.util;

import com.google.common.io.ByteStreams;
import com.sismics.docs.core.dao.TagIconDao;
import com.sismics.docs.core.model.jpa.TagIcon;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import org.apache.commons.lang3.StringUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tag icons (#287): validating what a tag may claim as its icon, and owning the custom icon set's
 * storage.
 *
 * <p>This lives in {@code rest.util} rather than in the resource for the same reason
 * {@link TagMaintenanceUtil} does: {@code rest.resource} may not reach into {@code core.dao}, and
 * the architecture test freezes the existing violations so a new one cannot be added. Everything
 * here that touches a DAO or the file store is therefore called from the resource, not done in
 * it.</p>
 *
 * @author fmaass
 */
public final class TagIconUtil {
    private TagIconUtil() {
        // Static utility
    }

    /**
     * The upload size cap.
     *
     * <p>32 KiB, which is generous for something drawn at 16 pixels: a 16x16 PNG is a few hundred
     * bytes and the Font Awesome SVG the reporter pasted into the issue is 480. The cap exists
     * because an icon is stored on the server, served to every user on every page that draws the
     * tag, and reached through an endpoint an administrator can call repeatedly — none of which is
     * a good place for a multi-megabyte file.</p>
     */
    public static final int MAX_ICON_BYTES = 32 * 1024;

    /**
     * The longest emoji payload accepted, in UTF-16 code units. A ZWJ family is 11 and the longest
     * standard sequence is comfortably inside this; the cap is what refuses a pathological chain
     * of joiners, which would otherwise be one grapheme cluster of unbounded length.
     */
    public static final int MAX_EMOJI_LENGTH = 32;

    private static final String MIME_PNG = "image/png";
    private static final String MIME_SVG = "image/svg+xml";

    /** The PNG signature (PNG 1.2 §3.1). The first eight bytes of every PNG and of nothing else. */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

    /** One extended grapheme cluster and nothing else. */
    private static final Pattern SINGLE_GRAPHEME = Pattern.compile("\\A\\X\\z");

    /**
     * Elements an SVG icon may not contain, by LOCAL name. The SMIL animation elements are here
     * because they rewrite attributes at render time: a same-document href that passes the walk
     * could be retargeted to an external URL by a {@code <set attributeName="href" to="…">}.
     */
    private static final Set<String> FORBIDDEN_ELEMENTS = Set.of("script", "foreignobject",
            "set", "animate", "animatemotion", "animatetransform", "animatecolor");

    /** CSS that reaches for something. Refused in a style attribute and in a style element alike. */
    private static final Pattern CSS_FETCHES = Pattern.compile("url\\s*\\(|expression\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * Validates and normalises what a client asked a tag's icon to be.
     *
     * <p>Absent, empty or whitespace means NO icon, which is a legitimate answer and the state
     * every tag was in before this feature existed — it is how an icon is taken back off a tag.
     * Anything else must be one of the two forms the column understands, and must be a form this
     * server can actually draw: an emoji it recognises as one emoji, or an icon that is in the set
     * right now.</p>
     *
     * @param icon Requested icon reference
     * @return The value to store, or null for no icon
     */
    public static String validateIconReference(String icon) {
        if (StringUtils.isBlank(icon)) {
            return null;
        }
        String value = icon.trim();

        if (value.startsWith(TagIcon.EMOJI_PREFIX)) {
            String emoji = value.substring(TagIcon.EMOJI_PREFIX.length());
            if (!isSingleEmoji(emoji)) {
                throw new ClientException("ValidationError",
                        "The icon must be exactly one emoji");
            }
            return TagIcon.EMOJI_PREFIX + emoji;
        }

        if (value.startsWith(TagIcon.SET_PREFIX)) {
            String iconId = value.substring(TagIcon.SET_PREFIX.length());
            // Read back rather than trusted: an icon can be deleted between the picker loading the
            // set and the form being saved, and a tag holding a reference to something that is
            // gone is exactly the broken-image state the whole deletion path exists to prevent.
            //
            // ACCEPTED RISK, deliberately unlocked. This read and the tag write that follows it are
            // not atomic against an administrator deleting the same icon in between, so a reference
            // to a just-deleted icon can still be stored. The window is the few milliseconds
            // between the two, it needs an admin delete and a user save to fall inside it, and this
            // is a deployment of a handful of users with one or two admins.
            //
            // What makes it acceptable is that the outcome is self-recovering rather than wrong:
            // the tag ends up pointing at an icon that no longer exists, the serving endpoint
            // answers 404 for it, and both the chip's `<img @error>` handler and the picker's
            // render-time lookup turn that into NO icon — the same state the tag would have had if
            // the delete had won the race outright. Nobody sees a broken image and no data is lost.
            // Locking the icon set on every tag save would add a contention point to the most
            // common write in the application to prevent a cosmetic, self-healing outcome.
            if (new TagIconDao().getActiveById(iconId) == null) {
                throw new ClientException("IconNotFound",
                        MessageFormat.format("Icon not found: {0}", iconId));
            }
            return TagIcon.setReference(iconId);
        }

        throw new ClientException("ValidationError",
                "The icon must be an emoji or an icon from the icon set");
    }

    /**
     * True when {@code value} is exactly ONE emoji.
     *
     * <p>Three conditions, and the third is the one that is easy to miss. The value must be a
     * single extended grapheme cluster (so a ZWJ family, a flag or a skin-toned emoji counts as
     * one, while two emoji side by side do not); every code point in it must belong to an emoji
     * sequence; and at least one code point must be something that actually DRAWS — because
     * Unicode gives the {@code Emoji} property to the plain ASCII digits and to {@code #} and
     * {@code *}, so "every code point is an emoji" alone would accept {@code 1} as a tag icon.
     * Extended_Pictographic covers the ordinary emoji, the regional-indicator range covers flags,
     * and U+20E3 covers the keycaps — which is exactly the set of things {@code 1} is not and
     * {@code 1️⃣} is.</p>
     *
     * @param emoji Candidate
     * @return True when it is one emoji
     */
    public static boolean isSingleEmoji(String emoji) {
        if (StringUtils.isEmpty(emoji) || emoji.length() > MAX_EMOJI_LENGTH) {
            return false;
        }
        if (!SINGLE_GRAPHEME.matcher(emoji).matches()) {
            return false;
        }
        boolean significant = false;
        for (int i = 0; i < emoji.length(); ) {
            int codePoint = emoji.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isEmoji(codePoint) && !Character.isEmojiComponent(codePoint)
                    && codePoint != 0xFE0E && codePoint != 0xFE0F) {
                return false;
            }
            if (Character.isExtendedPictographic(codePoint)
                    || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
                    || codePoint == 0x20E3) {
                significant = true;
            }
        }
        return significant;
    }

    /**
     * Returns the whole icon set.
     *
     * @return Icons, oldest first
     */
    public static List<TagIcon> list() {
        return new TagIconDao().findAll();
    }

    /**
     * Returns one icon that still exists, or null.
     *
     * @param id Icon ID
     * @return Icon, or null
     */
    public static TagIcon getActive(String id) {
        return new TagIconDao().getActiveById(id);
    }

    /**
     * Adds an uploaded image to the icon set.
     *
     * <p>The row is written first and the file second, so the file's name is the row's id and the
     * two cannot drift apart. A failed write deletes whatever it managed to produce and throws,
     * which rolls the row back with the request's transaction — the alternative order would leave
     * a file no row knows about every time the database refused.</p>
     *
     * @param name Icon name
     * @param inputStream Uploaded bytes
     * @param userId Uploading administrator
     * @return Created icon ID
     */
    public static String create(String name, InputStream inputStream, String userId) {
        String iconName = ValidationUtil.validateLength(name, "name", 1, 50, false);
        byte[] content = readBounded(inputStream);
        String mimeType = detectMimeType(content);

        String iconId = new TagIconDao().create(new TagIcon()
                .setName(iconName)
                .setMimeType(mimeType)
                .setUserId(userId));

        Path path = iconPath(iconId);
        try {
            Files.copy(new java.io.ByteArrayInputStream(content), path,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The write already failed; the row is about to roll back either way.
            }
            throw new ServerException("CopyError", "Error writing the icon to the icon store", e);
        }
        return iconId;
    }

    /**
     * Removes an icon from the set and clears it off every tag that used it.
     *
     * <p>The FILE is deliberately left where it is. Deleting it would mean an unrecoverable loss
     * on a mis-click, and it is at most 32 KiB; the row is what decides whether the icon exists,
     * and the serving endpoint reads the row first. This is the same shape as a soft-deleted
     * document keeping its file.</p>
     *
     * @param id Icon ID
     * @return Number of tags whose icon was cleared, or empty when no live icon has that ID
     */
    public static OptionalInt delete(String id) {
        return new TagIconDao().delete(id);
    }

    /**
     * The file backing one icon. The id comes from the database and is a UUID, never a
     * request-supplied string, so no traversal is representable — and the resolve is against the
     * icon directory, which is the second guard.
     *
     * @param id Icon ID
     * @return Path in the icon store
     */
    public static Path iconPath(String id) {
        return DirectoryUtil.getTagIconDirectory().resolve(id);
    }

    /**
     * Reads an upload, refusing anything over the cap. The stream is consumed only up to the limit
     * plus one byte, so a sender that keeps writing cannot make the server buffer it.
     *
     * @param inputStream Uploaded bytes
     * @return The content
     */
    private static byte[] readBounded(InputStream inputStream) {
        byte[] content;
        try {
            content = ByteStreams.toByteArray(ByteStreams.limit(inputStream, MAX_ICON_BYTES + 1L));
        } catch (IOException e) {
            throw new ServerException("ReadError", "Error reading the uploaded icon", e);
        }
        if (content.length > MAX_ICON_BYTES) {
            throw new ClientException("PayloadTooLarge", MessageFormat.format(
                    "The icon exceeds the {0} byte limit", MAX_ICON_BYTES));
        }
        if (content.length == 0) {
            throw new ClientException("ValidationError", "An image is required");
        }
        return content;
    }

    /**
     * Decides what an upload IS, from its bytes.
     *
     * <p>The client's declared content type and file name are ignored on purpose. The value
     * decided here is stored and replayed as the {@code Content-Type} when the icon is served, so
     * trusting the client would let an administrator's browser be told that an arbitrary file is
     * an image — the shape of a stored-content attack. A file that is neither a PNG nor an SVG is
     * refused rather than stored as {@code application/octet-stream}: only two types can be drawn
     * in a tag chip.</p>
     *
     * @param content Uploaded bytes
     * @return The media type to store and later serve
     */
    private static String detectMimeType(byte[] content) {
        if (startsWithPngSignature(content)) {
            return MIME_PNG;
        }
        String text = decodeUtf8(content);
        if (text == null) {
            throw new ClientException("InvalidImageType", "An icon must be a PNG or an SVG image");
        }
        // An SVG is a DOCUMENT, and a same-origin one can script. It is PARSED and walked rather
        // than pattern-matched: a substring scan for "<script" never sees <svg:script>, and one for
        // an external URL cannot tell an attribute from a coordinate. The serving endpoint
        // additionally answers under a policy that forbids the document everything.
        //
        // Neither is the security boundary — an administrator can already install custom JavaScript
        // through /theme/script, and only an administrator can upload an icon — but an icon is a
        // file the whole instance loads, so it is worth not being the easy way in.
        Document document = parseXml(text);
        if (document == null || document.getDocumentElement() == null
                || !"svg".equals(localNameOf(document.getDocumentElement()))) {
            // Not well-formed XML, or well-formed but not an SVG (an HTML page, say). Either way it
            // is not one of the two types an icon may be.
            throw new ClientException("InvalidImageType", "An icon must be a PNG or an SVG image");
        }
        refuseUnsafeNodes(document);
        return MIME_SVG;
    }

    private static boolean startsWithPngSignature(byte[] content) {
        if (content.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (content[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /** Decodes strictly: content that is not valid UTF-8 is not an SVG, it is binary. */
    private static String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Parses SVG bytes with every external-input door shut, or returns null when the content is not
     * well-formed XML.
     *
     * <p>The configuration IS the guard, so it fails CLOSED: a JDK that cannot be told to refuse
     * doctypes is a JDK on which this method cannot honour its contract, and it says so rather than
     * quietly parsing with the defaults. {@code disallow-doctype-decl} is what makes a DOCTYPE — and
     * with it every entity-expansion and file-reading trick — a parse error rather than something
     * the walk below has to recognise.</p>
     *
     * @param text SVG source
     * @return The parsed document, or null when it is not well-formed XML
     */
    private static Document parseXml(String text) {
        DocumentBuilder builder;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            // Namespace-aware so that <svg:script> reports the LOCAL name "script".
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            // JAXP 1.5's two access properties are BEST-EFFORT, and only these two.
            //
            // This application ships xerces:xercesImpl, which JAXP selects ahead of the JDK parser
            // and which does not recognise them ("Property '…/accessExternalDTD' is not
            // recognized." — verified against 2.12.2). They are set anyway for whichever parser
            // does honour them, and their absence costs nothing here: they restrict what a DOCTYPE
            // or a schema declaration may REACH, and disallow-doctype-decl above has already made
            // any DOCTYPE a fatal parse error, while no schema validation is switched on. The
            // features that actually carry the guarantee are set above and are NOT optional.
            trySetProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
            trySetProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new ServerException("XmlConfigError",
                    "The XML parser could not be hardened, so an SVG cannot be validated", e);
        }
        // Nothing is ever fetched, and a parse error is a refusal rather than a log line.
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException e) throws SAXException {
                throw e;
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        try {
            return builder.parse(new InputSource(new StringReader(text)));
        } catch (SAXException | IOException e) {
            return null;
        }
    }

    /** Sets an optional JAXP property, ignoring a parser that does not recognise it. */
    private static void trySetProperty(DocumentBuilderFactory factory, String name) {
        try {
            factory.setAttribute(name, "");
        } catch (IllegalArgumentException e) {
            // See the note at the call site: this parser does not know the property, and the
            // guarantee does not rest on it.
        }
    }

    /**
     * Walks the whole document and refuses anything an ICON has no business containing.
     *
     * <p>Deliberately a walk over parsed nodes, not a search over text: the element name is taken
     * after namespace resolution, so a prefix cannot hide it, and an attribute value is the decoded
     * value rather than whatever the source happened to spell.</p>
     *
     * @param node Node to check, with its subtree
     */
    private static void refuseUnsafeNodes(Node node) {
        switch (node.getNodeType()) {
            case Node.PROCESSING_INSTRUCTION_NODE ->
                    // The XML declaration is not a node; anything that IS one is an instruction to
                    // the renderer, e.g. <?xml-stylesheet href="..."?>.
                    refuse("a processing instruction");
            case Node.DOCUMENT_TYPE_NODE -> refuse("a DOCTYPE");
            case Node.ENTITY_REFERENCE_NODE -> refuse("an entity reference");
            case Node.ELEMENT_NODE -> refuseUnsafeElement((Element) node);
            default -> {
                // Text, comments and the document itself carry nothing to refuse.
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            refuseUnsafeNodes(children.item(i));
        }
    }

    private static void refuseUnsafeElement(Element element) {
        String name = localNameOf(element);
        if (FORBIDDEN_ELEMENTS.contains(name)) {
            refuse("a <" + name + "> element");
        }
        if ("style".equals(name)) {
            refuseFetchingCss(element.getTextContent());
        }

        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String attributeName = localNameOf(attribute);
            String value = attribute.getNodeValue();

            // Every event handler, whatever its case and whatever its namespace prefix.
            if (attributeName.startsWith("on")) {
                refuse("an " + attributeName + " event handler");
            }
            // href in either spelling — the plain SVG2 one and the legacy xlink one both resolve to
            // the local name "href". A reference into the SAME document is what a gradient or a
            // <use> needs; anything else reaches off the page, and an icon that fetches from another
            // host reports every viewer of every tagged document to that host.
            if ("href".equals(attributeName) && !isSameDocumentReference(value)) {
                refuse("an external reference on <" + name + ">");
            }
            if ("style".equals(attributeName)) {
                refuseFetchingCss(value);
            }
        }
    }

    /** True for a reference that stays inside this document, which is the only kind allowed. */
    private static boolean isSameDocumentReference(String value) {
        return value != null && value.strip().startsWith("#");
    }

    private static void refuseFetchingCss(String css) {
        if (css != null && CSS_FETCHES.matcher(css).find()) {
            refuse("CSS that fetches a resource");
        }
    }

    /**
     * An element or attribute's name with any namespace prefix removed, lowercased.
     *
     * <p>{@link Node#getLocalName()} is null for a node built without namespace awareness, so the
     * prefix is stripped by hand in that case rather than trusted to be absent.</p>
     */
    private static String localNameOf(Node node) {
        String local = node.getLocalName();
        if (local == null) {
            local = node.getNodeName();
            int colon = local.indexOf(':');
            if (colon >= 0) {
                local = local.substring(colon + 1);
            }
        }
        return local.toLowerCase(Locale.ROOT);
    }

    private static void refuse(String what) {
        throw new ClientException("InvalidImageContent",
                "The SVG contains " + what + "; an icon may only draw shapes");
    }
}
