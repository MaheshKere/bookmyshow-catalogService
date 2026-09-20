import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/** Run from repository root: java scripts/GenerateDevKeys.java */
class GenerateDevKeys {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(".local", "jwt");
        Path privateKey = directory.resolve("private.pem");
        Path publicKey = directory.resolve("public.pem");
        if (Files.exists(privateKey) || Files.exists(publicKey)) {
            throw new IllegalStateException("Keys already exist. Reuse them; do not silently rotate active tokens.");
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        Files.createDirectories(directory);
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardOpenOption.CREATE_NEW);
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardOpenOption.CREATE_NEW);
        System.out.println("Development keys created in .local/jwt. Give only identity-service the private key.");
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n";
    }
}
