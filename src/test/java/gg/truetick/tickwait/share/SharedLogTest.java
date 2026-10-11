package gg.truetick.tickwait.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedLogTest {
    @TempDir
    Path dir;

    @Test
    void remembersWhereEachReportWentAndReadsOldLines() throws IOException {
        Path file = dir.resolve("shared.tsv");
        Files.write(file, Collections.singletonList("oldid12345\thttps://truetick.gg/tickwait/r/oldid12345\ttok\t1"),
                StandardCharsets.UTF_8);
        SharedLog log = new SharedLog(file);
        log.add("newid12345", "https://dev.truetick.gg/tickwait/r/newid12345", "tok2", 2,
                "https://api.dev.truetick.gg/v1/public/tickwait/reports");
        assertNull(log.find("oldid12345").apiUrl);
        assertEquals("https://api.dev.truetick.gg/v1/public/tickwait/reports", log.find("newid12345").apiUrl);
        log.remove("oldid12345");
        assertEquals(1, log.entries().size());
        assertEquals("tok2", log.find("newid12345").deleteToken);
    }
}
