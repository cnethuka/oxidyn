package dev.oxidyn.ffi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Copies the bundled library out of the jar so the OS loader can open it.
 *
 * <p>The target folder is named after the file's hash. That way an updated
 * mod never tries to overwrite a library some other running instance still
 * has open (Windows really doesn't like that), and an unchanged one is reused.
 */
final class LibraryExtractor {
    private LibraryExtractor() {
    }

    static Path extract(Path cacheRoot) throws IOException {
        String resource = "/natives/" + Platform.id() + "/" + Platform.libraryFileName();
        byte[] bytes;
        try (InputStream in = LibraryExtractor.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("this build of Oxidyn has no native library for " + Platform.id());
            }
            bytes = in.readAllBytes();
        }

        Path dir = cacheRoot.resolve(sha1(bytes).substring(0, 16));
        Path target = dir.resolve(Platform.libraryFileName());
        if (Files.isRegularFile(target) && Files.size(target) == bytes.length) {
            return target;
        }

        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "oxidyn", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
