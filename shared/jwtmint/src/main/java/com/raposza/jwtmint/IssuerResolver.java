// Copyright (c) 2026 bentzn
// SPDX-License-Identifier: Apache-2.0
package com.raposza.jwtmint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * THE issuer - one value, resolved once, used by everything.
 *
 * <h2>Why this replaced a per-request derivation</h2>
 *
 * The mint used to answer the issuer question from whatever URL the request
 * happened to arrive on. That is correct for exactly one caller at a time and
 * wrong the moment there are two: a participant inside a virtual machine
 * fetches the discovery document at the routable address and records
 * `issuer=http://192.168.x.x:32002`, a curl on the host mints a token at
 * loopback and that token carries `iss=http://localhost:32002`, and the two do
 * not match. OpenID Connect Discovery 1.0 section 4.3 and RFC 8414 section 3.3
 * both require the `issuer` in the metadata and the `iss` in a token to be the
 * SAME string, compared literally.
 *
 * So one value is resolved at startup and every consumer reads it: the
 * discovery document's `issuer`, every endpoint URL that document advertises,
 * and the `iss` claim of every token minted.
 *
 * <h2>How it is resolved</h2>
 *
 * `raposza.jwtmint.issuer` wins outright when it is set, and setting it is
 * the answer whenever this machine's address is not the address the verifier
 * will use - behind a port forward, behind a proxy, or on a host with several
 * candidate interfaces.
 *
 * When it is blank the value is `http://<address>:<port>`, where the address is
 * the lowest site-local IPv4 address this machine carries, and loopback only
 * when there is no other. Loopback is not the default because a virtual machine
 * cannot reach it, and a discovery document advertising an issuer the client
 * cannot fetch is the failure this class exists to remove.
 *
 * THE ADDRESS IS SORTED, not taken in interface order. Interface enumeration
 * order is not stable across boots, and an issuer that changes when the machine
 * restarts invalidates every token that was minted before it.
 *
 * <h2>The paths</h2>
 *
 * The `/oauth2/` names are a PROJECT CONVENTION and form no part of any
 * protocol. A conforming client reads them out of the discovery document and
 * never builds them; they are constants here so that the document and the
 * service cannot disagree about what they are.
 *
 * Author Claude/bentzn
 * Generated 2026-08-21T00:00:00Z
 */
@Component
public final class IssuerResolver {

    /** OpenID Connect Discovery 1.0 section 4. */
    public static final String STR_PATH_DISCOVERY_OIDC = "/.well-known/openid-configuration";

    /** RFC 8414 section 3 - the same document, at the OAuth location. */
    public static final String STR_PATH_DISCOVERY_OAUTH =
            "/.well-known/oauth-authorization-server";

    /** Project convention. A client takes this from `token_endpoint`. */
    public static final String STR_PATH_TOKEN = "/oauth2/token";

    /** Project convention. A client takes this from `jwks_uri`. */
    public static final String STR_PATH_JWKS = "/oauth2/jwks";

    /** Project convention. Advertised nowhere, because nothing standard has it. */
    public static final String STR_PATH_JWKS_PRIVATE = "/oauth2/jwks-private";

    /** Project convention. A client takes this from `authorization_endpoint`. */
    public static final String STR_PATH_AUTHORIZE = "/oauth2/authorize";

    /** Project convention. A client takes this from `userinfo_endpoint`. */
    public static final String STR_PATH_USERINFO = "/oauth2/userinfo";

    /** Project convention. A client takes this from `end_session_endpoint`. */
    public static final String STR_PATH_LOGOUT = "/oauth2/logout";

    private static final Logger log = LoggerFactory.getLogger(IssuerResolver.class);

    private final String strIssuer;

    private final boolean flagPinned;


    /**
     * @param settings the configuration, for a pinned issuer
     * @param nPort the port this service listens on
     */
    public IssuerResolver(MintSettings settings, @Value("${server.port:32002}") int nPort) {
        this.flagPinned = settings.strIssuerFixed() != null;
        this.strIssuer = flagPinned
                ? strNoTrailingSlash(settings.strIssuerFixed())
                : "http://" + strHostRoutable() + ":" + nPort;
        log.info("issuer {} {}", strIssuer,
                flagPinned ? "(raposza.jwtmint.issuer)" : "(resolved at startup)");
    }


    /**
     * @return the one issuer, written into the discovery document and into
     *         every token
     */
    public String strIssuer() {
        return strIssuer;
    }


    /**
     * @return true when `raposza.jwtmint.issuer` set it
     */
    public boolean flagPinned() {
        return flagPinned;
    }


    /**
     * @param strPath a path beginning with a slash
     * @return the absolute URL a client is to be given for it
     */
    public String strUrl(String strPath) {
        return strIssuer + strPath;
    }


    /**
     * The URL THIS request arrived on, which is not the issuer.
     *
     * Used only by the plain-text index page, where the point is to echo back
     * the address the reader actually typed. Nothing that a verifier compares
     * may be built from it.
     *
     * @return the scheme, host, port and context path of the current request
     */
    static String strOfRequest() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }


    /**
     * @param strUrl a URL
     * @return it without a trailing slash, because the issuer is compared as a
     *         literal string and one slash is one mismatch
     */
    private static String strNoTrailingSlash(String strUrl) {
        String strOut = strUrl.trim();
        while (strOut.endsWith("/")) {
            strOut = strOut.substring(0, strOut.length() - 1);
        }
        return strOut;
    }


    /**
     * @return the lowest site-local IPv4 address of this machine, or any other
     *         non-loopback IPv4 address when there is none, or `127.0.0.1`
     */
    private static String strHostRoutable() {
        List<String> lstSiteLocal = new ArrayList<>();
        List<String> lstOther = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> enumIface = NetworkInterface.getNetworkInterfaces();
            while (enumIface.hasMoreElements()) {
                NetworkInterface iface = enumIface.nextElement();
                if (!iface.isUp() || iface.isLoopback())
                    continue;

                Enumeration<InetAddress> enumAddr = iface.getInetAddresses();
                while (enumAddr.hasMoreElements()) {
                    InetAddress addr = enumAddr.nextElement();
                    if (!(addr instanceof Inet4Address))
                        continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress())
                        continue;
                    if (addr.isSiteLocalAddress())
                        lstSiteLocal.add(addr.getHostAddress());
                    else
                        lstOther.add(addr.getHostAddress());
                }
            }
        }
        catch (SocketException ex) {
            log.warn("cannot enumerate interfaces, falling back to loopback", ex);
        }

        List<String> lstPick = lstSiteLocal.isEmpty() ? lstOther : lstSiteLocal;
        if (lstPick.isEmpty())
            return "127.0.0.1";

        Collections.sort(lstPick);
        return lstPick.get(0);
    }

}
