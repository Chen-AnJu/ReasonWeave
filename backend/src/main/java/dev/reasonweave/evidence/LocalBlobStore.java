package dev.reasonweave.evidence;

import dev.reasonweave.config.ReasonWeaveProperties;
import dev.reasonweave.runtime.InstanceScope;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LocalBlobStore {
    private static final Pattern CONTENT_ADDRESSED_KEY = Pattern.compile(
        "^(" + Pattern.quote(InstanceScope.ID)
            + ")/((?:evt_)[A-Za-z0-9_-]+)/sha256/([a-f0-9]{64})$"
    );
    private final Path root;
    private final Path stagingRoot;

    public LocalBlobStore(ReasonWeaveProperties properties) throws IOException {
        this.root = Path.of(properties.blobRoot()).toAbsolutePath().normalize();
        this.stagingRoot = root.resolve(".staging").normalize();
        Files.createDirectories(root);
        Files.createDirectories(stagingRoot);
    }

    public StoredBlob storeContentAddressed(
        String workspaceId,
        String eventId,
        String checksumSha256,
        byte[] bytes
    ) {
        if (checksumSha256 == null || !checksumSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Blob checksum must be a lowercase SHA-256 value");
        }
        Path target = root.resolve(workspaceId)
            .resolve(eventId)
            .resolve("sha256")
            .resolve(checksumSha256)
            .normalize();
        ensureWithinRoot(target);
        Path staged = stagingRoot.resolve(UUID.randomUUID() + ".tmp").normalize();
        ensureWithinRoot(staged);
        try {
            Files.write(staged, bytes, StandardOpenOption.CREATE_NEW);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                return new StoredBlob(key(target), false);
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, target);
            } catch (FileAlreadyExistsException exception) {
                return new StoredBlob(key(target), false);
            }
            return new StoredBlob(key(target), true);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist evidence blob", exception);
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // A later staging cleanup can remove an interrupted temporary file.
            }
        }
    }

    public byte[] read(String blobKey) {
        Path target = resolve(blobKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read evidence blob", exception);
        }
    }

    public void discardIfCreated(StoredBlob storedBlob) {
        if (storedBlob == null || !storedBlob.created()) {
            return;
        }
        Path target = resolve(storedBlob.key());
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to remove an unreferenced evidence blob", exception);
        }
    }

    public int cleanupStaging(Duration minimumAge) {
        Instant threshold = Instant.now().minus(minimumAge);
        int removed = 0;
        try (var files = Files.list(stagingRoot)) {
            for (Path path : files.filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(threshold)
                    && Files.deleteIfExists(path)) {
                    removed++;
                }
            }
            return removed;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to clean evidence staging files", exception);
        }
    }

    public List<BlobCandidate> findContentAddressedCandidates(Duration minimumAge) {
        Instant threshold = Instant.now().minus(minimumAge);
        List<BlobCandidate> candidates = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths
                .filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS))
                .toList()) {
                String blobKey = key(path.toAbsolutePath().normalize());
                Matcher matcher = CONTENT_ADDRESSED_KEY.matcher(blobKey);
                if (matcher.matches() && Files.getLastModifiedTime(path).toInstant().isBefore(threshold)) {
                    candidates.add(new BlobCandidate(
                        blobKey,
                        matcher.group(1),
                        matcher.group(2),
                        matcher.group(3)
                    ));
                }
            }
            return List.copyOf(candidates);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect content-addressed evidence blobs", exception);
        }
    }

    public boolean discardCandidate(BlobCandidate candidate) {
        Matcher matcher = CONTENT_ADDRESSED_KEY.matcher(candidate.key());
        if (!matcher.matches()
            || !matcher.group(1).equals(candidate.workspaceId())
            || !matcher.group(2).equals(candidate.eventId())
            || !matcher.group(3).equals(candidate.checksumSha256())) {
            throw new IllegalArgumentException("Blob candidate does not match the content-addressed path contract");
        }
        Path target = resolve(candidate.key());
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to remove an unreferenced evidence blob", exception);
        }
    }

    private Path resolve(String blobKey) {
        if (blobKey == null || blobKey.isBlank()) {
            throw new IllegalArgumentException("Evidence has no blob key");
        }
        Path target = root.resolve(blobKey).normalize();
        ensureWithinRoot(target);
        return target;
    }

    private void ensureWithinRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Blob path escaped configured root");
        }
    }

    private String key(Path target) {
        return root.relativize(target).toString().replace('\\', '/');
    }

    public record StoredBlob(String key, boolean created) {}

    public record BlobCandidate(
        String key,
        String workspaceId,
        String eventId,
        String checksumSha256
    ) {}
}
