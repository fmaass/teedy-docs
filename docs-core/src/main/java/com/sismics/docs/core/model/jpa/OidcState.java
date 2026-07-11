package com.sismics.docs.core.model.jpa;

import com.google.common.base.MoreObjects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Date;

/**
 * OIDC authorization state entity.
 * Stores CSRF state, nonce, PKCE verifier, and return URL for in-flight OIDC logins.
 */
@Entity
@Table(name = "T_OIDC_STATE")
public class OidcState {
    @Id
    @Column(name = "OIS_ID_C", length = 36)
    private String id;

    @Column(name = "OIS_NONCE_C", nullable = false, length = 36)
    private String nonce;

    @Column(name = "OIS_CODEVERIFIER_C", length = 128)
    private String codeVerifier;

    @Column(name = "OIS_RETURNURL_C", length = 2000)
    private String returnUrl;

    /**
     * Provider fingerprint pinned at login: the effective OIDC issuer that started this flow.
     * The callback rejects the flow if the CURRENT effective issuer no longer matches, so an
     * authorization code minted for one provider is never exchanged with another after a live
     * provider switch. Nullable for states created before this column existed (legacy drain).
     */
    @Column(name = "OIS_ISSUER_C", length = 500)
    private String issuer;

    /**
     * Provider fingerprint pinned at login: the effective OIDC client_id that started this flow.
     * Paired with {@link #issuer} in the callback's provider-binding check.
     */
    @Column(name = "OIS_CLIENTID_C", length = 500)
    private String clientId;

    @Column(name = "OIS_CREATEDATE_D", nullable = false)
    private Date createDate;

    public String getId() {
        return id;
    }

    public OidcState setId(String id) {
        this.id = id;
        return this;
    }

    public String getNonce() {
        return nonce;
    }

    public OidcState setNonce(String nonce) {
        this.nonce = nonce;
        return this;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public OidcState setCodeVerifier(String codeVerifier) {
        this.codeVerifier = codeVerifier;
        return this;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public OidcState setReturnUrl(String returnUrl) {
        this.returnUrl = returnUrl;
        return this;
    }

    public String getIssuer() {
        return issuer;
    }

    public OidcState setIssuer(String issuer) {
        this.issuer = issuer;
        return this;
    }

    public String getClientId() {
        return clientId;
    }

    public OidcState setClientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public OidcState setCreateDate(Date createDate) {
        this.createDate = createDate;
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .toString();
    }
}
