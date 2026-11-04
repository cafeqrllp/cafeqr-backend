package com.restaurant.pos.integration.ondc.crypto;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
@Service
public class OndcCryptoService {

    /**
     * Computes the BLAKE2b-512 digest of a string payload and returns it as a Base64 string.
     */
    public String calculateBlake2b512Digest(String payload) {
        byte[] input = payload.getBytes(StandardCharsets.UTF_8);
        Blake2bDigest digest = new Blake2bDigest(512);
        digest.update(input, 0, input.length);
        byte[] output = new byte[64];
        digest.doFinal(output, 0);
        return Base64.getEncoder().encodeToString(output);
    }

    /**
     * Signs data using the Ed25519 private key (using the 32-byte seed from the Base64 key).
     */
    public String signEd25519(String signingString, String privateKeyBase64) {
        byte[] decodedKey = Base64.getDecoder().decode(privateKeyBase64);
        byte[] seed = decodedKey.length >= 32 ? Arrays.copyOfRange(decodedKey, 0, 32) : decodedKey;

        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);

        byte[] data = signingString.getBytes(StandardCharsets.UTF_8);
        signer.update(data, 0, data.length);
        byte[] signature = signer.generateSignature();

        return Base64.getEncoder().encodeToString(signature);
    }

    /**
     * Generates the standard Beckn / ONDC Authorization header.
     * Header format:
     * Signature keyId="{subscriber_id}|{ukid}|ed25519",algorithm="ed25519",created="{created}",expires="{expires}",headers="(created) (expires) digest",signature="{signature}"
     */
    public String generateAuthorizationHeader(String payloadJson, String subscriberId, String ukid, String privateKeyBase64) {
        long created = Instant.now().getEpochSecond();
        long expires = created + 300; // 5-minute validity window

        String digestB64 = calculateBlake2b512Digest(payloadJson);
        String formattedDigest = "BLAKE-512=" + digestB64;

        String signingString = "(created): " + created + "\n(expires): " + expires + "\ndigest: " + formattedDigest;
        String signatureB64 = signEd25519(signingString, privateKeyBase64);

        String keyId = subscriberId + "|" + ukid + "|ed25519";
        return String.format(
                "Signature keyId=\"%s\",algorithm=\"ed25519\",created=\"%d\",expires=\"%d\",headers=\"(created) (expires) digest\",signature=\"%s\"",
                keyId, created, expires, signatureB64
        );
    }
}
