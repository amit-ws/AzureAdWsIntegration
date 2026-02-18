package com.ws.certificateJIT.k8;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public class CertificateUtil {

    /**
     * Generate RSA 2048-bit KeyPair
     */
    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    /**
     * Create X.509 Certificate Signing Request (CSR)
     * CN = username
     * O = groups (if provided)
     */
    public static String generateCSR(
            KeyPair keyPair,
            String username,
            List<String> groups) throws Exception {

        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, username);

        if (groups != null && !groups.isEmpty()) {
            for (String group : groups) {
                nameBuilder.addRDN(BCStyle.O, group);
            }
        }

        X500Name x500Name = nameBuilder.build();
        PKCS10CertificationRequestBuilder builder =
                new JcaPKCS10CertificationRequestBuilder(x500Name, keyPair.getPublic());

        ContentSigner signer =
                new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());

        PKCS10CertificationRequest csr = builder.build(signer);

        return encodePEM(csr);
    }

    /**
     * Encode private key to PEM format
     */
    public static String encodePrivateKeyPEM(KeyPair keyPair) throws IOException {
        return encodePEM(keyPair.getPrivate());
    }

    /**
     * Generic PEM encoder
     */
    public static String encodePEM(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(obj);
        }
        return sw.toString();
    }
}
