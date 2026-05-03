package messenger.ring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

public final class IdStorage {
    private final Path idFilePath;

    public IdStorage(Path idFilePath) {
        this.idFilePath = idFilePath;
    }

    public OptionalLong load() throws IOException {
        if (!Files.exists(idFilePath)) {
            return OptionalLong.empty();
        }
        String content = Files.readString(idFilePath, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(content));
    }

    public void save(long id) throws IOException {
        Path parent = idFilePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(idFilePath, Long.toString(id), StandardCharsets.UTF_8);
    }

    public Path idFilePath() {
        return idFilePath;
    }
}
